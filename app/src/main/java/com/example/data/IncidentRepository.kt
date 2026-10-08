package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.api.SigoApiService
import com.example.core.security.SessionManager
import com.example.core.security.SessionState
import com.example.data.db.AviDatabase
import com.example.data.db.IncidentEntity
import com.example.model.EstadoSincronizacion
import com.example.model.Incident
import com.example.model.LoginResponse
import com.example.model.RegistroSigoRequest
import com.example.model.RegistroSigoResponse
import com.example.model.UsuarioDto
import com.example.worker.SyncRegistroWorker
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

enum class ApiConnectionState(val label: String) {
    ONLINE("Conectado a SIGO"),
    OFFLINE("Servidor no disponible"),
    SIN_CONFIGURAR("API sin configurar")
}

class IncidentRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val database = AviDatabase.getInstance(appContext)
    private val dao = database.incidentDao()
    private val prefs: SharedPreferences = appContext.getSharedPreferences("avi_sigo_prefs", Context.MODE_PRIVATE)

    val sessionManager = SessionManager(appContext)

    private val scope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()

    // Configuración actual: URL predeterminada hacia la IP de desarrollo del usuario
    private var baseUrl: String = prefs.getString("sigo_base_url", SigoApiService.DEFAULT_BASE_URL)
        ?: SigoApiService.DEFAULT_BASE_URL

    private var sigoApi: SigoApiService = SigoApiService.create(baseUrl) {
        sessionManager.getToken()
    }

    private val _connectionState = MutableStateFlow(ApiConnectionState.OFFLINE)
    val connectionState: StateFlow<ApiConnectionState> = _connectionState.asStateFlow()

    // Vías activas reales de la plaza del operador. Si la consulta no está disponible,
    // se mantiene vacío para no bloquear el registro offline.
    private val _allowedVias = MutableStateFlow<Set<Int>>(emptySet())
    val allowedVias: StateFlow<Set<Int>> = _allowedVias.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncSummary = MutableStateFlow("Listo para registrar.")
    val lastSyncSummary: StateFlow<String> = _lastSyncSummary.asStateFlow()

    private val _sessionExpiredEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sessionExpiredEvent: SharedFlow<String> = _sessionExpiredEvent.asSharedFlow()

    // Flujo reactivo de incidencias persistidas en Room
    val incidentsFlow: Flow<List<Incident>> = dao.getAllFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    val pendientesCountFlow: Flow<Int> = dao.getPendientesCountFlow()

    init {
        verificarConexionSigo()
        scope.launch {
            cargarViasPermitidas()
        }
    }

    fun getBaseUrl(): String = baseUrl

    fun getUsuarioActual(): UsuarioDto? = sessionManager.getUsuario()

    fun getAllowedVias(): Set<Int> = _allowedVias.value

    fun isViaPermitida(via: Int): Boolean {
        val configured = _allowedVias.value
        return configured.isEmpty() || via in configured
    }

    suspend fun cargarViasPermitidas(
        plazaId: Long? = sessionManager.getUsuario()?.plazaId
    ): Set<Int> {
        if (plazaId == null || plazaId <= 0L || sessionManager.getToken().isNullOrBlank()) {
            _allowedVias.value = emptySet()
            return emptySet()
        }

        return try {
            val response = sigoApi.listarVias(plazaId)
            if (response.isSuccessful) {
                val vias = response.body()
                    .orEmpty()
                    .filter { it.activa }
                    .map { it.numero }
                    .filter { it > 0 }
                    .toSet()

                _allowedVias.value = vias
                Log.i(TAG, "Vías cargadas para plaza $plazaId: $vias")
                vias
            } else {
                Log.w(TAG, "No se pudieron cargar vías de plaza $plazaId: HTTP ${response.code()}")
                _allowedVias.value = emptySet()
                emptySet()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudieron cargar vías de la plaza: ${e.message}")
            _allowedVias.value = emptySet()
            emptySet()
        }
    }

    fun isUsuarioAutenticado(): Boolean = sessionManager.isLoggedIn()

    val sessionState: StateFlow<SessionState> = sessionManager.sessionState

    /**
     * Clasifica y traduce los errores técnicos diferenciando serialización, conexión y configuración
     */
    private fun clasificarErrorTecnico(e: Throwable): String {
        Log.e(TAG, "Excepción técnica en llamada API: ${e.javaClass.simpleName} - ${e.message}")
        return when {
            e is IllegalArgumentException && e.message?.contains("converter", ignoreCase = true) == true ->
                "Error interno de serialización JSON en Retrofit: ${e.message}. Verifique adaptadores Moshi."
            e is JsonDataException || e is JsonEncodingException ->
                "Error en el formato o estructura JSON de datos: ${e.message}"
            e is SocketTimeoutException ->
                "Tiempo de espera agotado al conectar con SIGO-BACK (Timeout). Verifique que el backend esté respondiendo en $baseUrl"
            e is ConnectException ->
                "No se pudo establecer conexión con el servidor en $baseUrl. Verifique que Spring Boot esté ejecutándose y la IP sea alcanzable."
            e is UnknownHostException ->
                "Host no encontrado ($baseUrl). Verifique la dirección IP configurada en la aplicación."
            else ->
                e.localizedMessage ?: "Error de comunicación con SIGO-BACK."
        }
    }

    /**
     * Autenticación con código de trabajador.
     * POST /api/avi/auth/login
     */
    suspend fun login(codigo: Int): Result<LoginResponse> {
        return withContext(Dispatchers.IO) {
            if (codigo <= 0) {
                return@withContext Result.failure(IllegalArgumentException("El código de trabajador debe ser mayor a 0."))
            }

            try {
                val response = sigoApi.login(com.example.model.LoginRequest(codigo))
                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    sessionManager.saveSession(loginResponse)
                    _connectionState.value = ApiConnectionState.ONLINE

                    Log.i(TAG, "Login exitoso para código de operador: $codigo")

                    // Cargar catálogo real de vías de la plaza para validar el motor de voz.
                    cargarViasPermitidas(loginResponse.usuario?.plazaId)

                    // Al iniciar sesión, sincronizar pendientes locales si los hubiera
                    scope.launch {
                        sincronizarPendientes()
                    }

                    Result.success(loginResponse)
                } else {
                    val errorMsg = when (response.code()) {
                        400 -> "Datos inválidos (HTTP 400). Verifique el código de trabajador."
                        401 -> "Código de operador no autorizado o inexistente (HTTP 401). Verifique con el administrador."
                        403 -> "Acceso denegado (HTTP 403). No cuenta con permisos para operar."
                        404 -> "Endpoint no encontrado (HTTP 404). Verifique la ruta /api/avi/auth/login en su backend."
                        500 -> "Error interno del servidor SIGO-BACK (HTTP 500). Revise la consola de Spring Boot."
                        else -> "Error en SIGO-BACK (${response.code()}): ${response.message()}"
                    }
                    Log.w(TAG, "Fallo HTTP en login: Código ${response.code()} - $errorMsg")
                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Throwable) {
                _connectionState.value = ApiConnectionState.OFFLINE
                val mensajeClasificado = clasificarErrorTecnico(e)
                Result.failure(Exception(mensajeClasificado))
            }
        }
    }

    /**
     * Cierra la sesión activa.
     * IMPORTANTE: No borra los registros pendientes almacenados en Room.
     */
    fun logout() {
        sessionManager.clearSession()
        _allowedVias.value = emptySet()
        _lastSyncSummary.value = "Sesión cerrada. Los registros pendientes permanecen en el dispositivo."
    }

    fun updateConfig(newBaseUrl: String): Boolean {
        var trimmed = newBaseUrl.trim()
        if (trimmed.isBlank()) return false
        if (!trimmed.endsWith("/")) {
            trimmed = "$trimmed/"
        }

        baseUrl = trimmed
        prefs.edit()
            .putString("sigo_base_url", baseUrl)
            .apply()

        sigoApi = SigoApiService.create(baseUrl) {
            sessionManager.getToken()
        }
        verificarConexionSigo()
        scope.launch {
            cargarViasPermitidas()
        }
        return true
    }

    fun verificarConexionSigo() {
        if (baseUrl.isBlank()) {
            _connectionState.value = ApiConnectionState.SIN_CONFIGURAR
            return
        }

        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    sigoApi.getStatus()
                }
                if (response.isSuccessful) {
                    _connectionState.value = ApiConnectionState.ONLINE
                } else {
                    _connectionState.value = ApiConnectionState.OFFLINE
                }
            } catch (e: Exception) {
                _connectionState.value = ApiConnectionState.OFFLINE
            }
        }
    }

    /**
     * Guarda el evento vehicular inmediatamente en Room Database con estado PENDIENTE,
     * y luego intenta enviarlo a SIGO-BACK.
     * Se genera un UUID único que NO cambia en reintentos.
     * fechaHoraEvento conserva estrictamente la hora en que ocurrió el evento.
     */
    suspend fun registrarIncidencia(
        placa: String,
        via: Int?,
        accion: String,
        fechaHoraEvento: String,
        textoReconocido: String,
        plazaIdFallback: String = "PLAZA-01"
    ): Result<Incident> {
        return withContext(Dispatchers.IO) {
            val placaNormalizada = placa.uppercase().replace("-", "").replace(" ", "").trim()
            val accionNormalizada = if (accion.equals("DERIVADO", ignoreCase = true)) "DERIVADO" else "FUGA"

            // Validaciones locales antes de registrar
            if (placaNormalizada.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("La placa no puede estar vacía."))
            }
            if (via == null || via <= 0) {
                return@withContext Result.failure(IllegalArgumentException("La vía no puede estar vacía o ser menor a 1."))
            }
            if (!isViaPermitida(via)) {
                val permitidas = _allowedVias.value.sorted().joinToString(", ")
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "La vía $via no está habilitada para esta plaza. Vías disponibles: $permitidas"
                    )
                )
            }

            val plazaActiva = sessionManager.getUsuario()?.plaza ?: plazaIdFallback

            val incident = Incident(
                id = UUID.randomUUID().toString(),
                placa = placaNormalizada,
                via = via,
                accion = accionNormalizada,
                plazaId = plazaActiva,
                fechaHoraEvento = fechaHoraEvento,
                textoReconocido = textoReconocido,
                estadoSincronizacion = EstadoSincronizacion.PENDIENTE,
                intentosSincronizacion = 0,
                ultimoError = null
            )

            // 1. Guardar primero en Room
            val entity = IncidentEntity.fromDomain(incident)
            dao.insert(entity)

            // 2. Intentar envío inmediato si hay URL
            val enviado = enviarRegistroASigo(entity)
            if (enviado.estadoSincronizacion != EstadoSincronizacion.SINCRONIZADO) {
                // Programar reintento automático con WorkManager
                SyncRegistroWorker.enqueueImmediateSync(appContext)
            }

            Result.success(enviado)
        }
    }

    /**
     * Envía un registro a SIGO y actualiza Room según la respuesta del backend.
     */
    private suspend fun enviarRegistroASigo(entity: IncidentEntity): Incident {
        var updatedEntity = entity.copy(
            intentosSincronizacion = entity.intentosSincronizacion + 1,
            estadoSincronizacion = EstadoSincronizacion.SINCRONIZANDO.name
        )
        dao.update(updatedEntity)

        val token = sessionManager.getToken()
        if (token.isNullOrBlank()) {
            val errorMsg = "Sesión no iniciada. Inicie sesión para enviar a SIGO."
            updatedEntity = updatedEntity.copy(
                estadoSincronizacion = EstadoSincronizacion.PENDIENTE.name,
                ultimoError = errorMsg
            )
            dao.update(updatedEntity)
            return updatedEntity.toDomain()
        }

        try {
            val request = RegistroSigoRequest(
                id = entity.id,
                placa = entity.placa,
                via = entity.via,
                accion = entity.accion,
                fechaHoraEvento = entity.fechaHoraEvento,
                textoReconocido = entity.textoReconocido
            )

            val response = sigoApi.registrarEvento(request)

            when (response.code()) {
                200, 201 -> {
                    val body = response.body()
                    updatedEntity = updatedEntity.copy(
                        estadoSincronizacion = EstadoSincronizacion.SINCRONIZADO.name,
                        fechaHoraRecepcion = body?.fechaHoraRecepcion,
                        ultimoError = null
                    )
                    dao.update(updatedEntity)
                    _connectionState.value = ApiConnectionState.ONLINE
                    return updatedEntity.toDomain()
                }
                401 -> {
                    // Token expirado o inválido
                    val errorMsg = "Token expirado o inválido (HTTP 401). Inicie sesión nuevamente."
                    updatedEntity = updatedEntity.copy(
                        estadoSincronizacion = EstadoSincronizacion.PENDIENTE.name,
                        ultimoError = errorMsg
                    )
                    dao.update(updatedEntity)
                    _sessionExpiredEvent.tryEmit("Su sesión ha expirado. Por favor ingrese su código nuevamente para sincronizar.")
                    return updatedEntity.toDomain()
                }
                409 -> {
                    // Conflicto de UUID reutilizado con datos diferentes
                    val errorMsg = "Conflicto en servidor (HTTP 409): UUID duplicado con datos divergentes."
                    updatedEntity = updatedEntity.copy(
                        estadoSincronizacion = EstadoSincronizacion.REQUIERE_REVISION.name,
                        ultimoError = errorMsg
                    )
                    dao.update(updatedEntity)
                    return updatedEntity.toDomain()
                }
                400 -> {
                    val errorMsg = "Datos rechazados por SIGO (HTTP 400)."
                    updatedEntity = updatedEntity.copy(
                        estadoSincronizacion = EstadoSincronizacion.REQUIERE_REVISION.name,
                        ultimoError = errorMsg
                    )
                    dao.update(updatedEntity)
                    return updatedEntity.toDomain()
                }
                500 -> {
                    val errorMsg = "Error interno en el servidor SIGO (HTTP 500)."
                    updatedEntity = updatedEntity.copy(
                        estadoSincronizacion = EstadoSincronizacion.PENDIENTE.name,
                        ultimoError = errorMsg
                    )
                    dao.update(updatedEntity)
                    return updatedEntity.toDomain()
                }
                else -> {
                    val errorMsg = "SIGO respondió HTTP ${response.code()}: ${response.message()}"
                    updatedEntity = updatedEntity.copy(
                        estadoSincronizacion = EstadoSincronizacion.PENDIENTE.name,
                        ultimoError = errorMsg
                    )
                    dao.update(updatedEntity)
                    return updatedEntity.toDomain()
                }
            }
        } catch (e: Throwable) {
            val errorMsg = clasificarErrorTecnico(e)
            updatedEntity = updatedEntity.copy(
                estadoSincronizacion = EstadoSincronizacion.PENDIENTE.name,
                ultimoError = errorMsg
            )
            dao.update(updatedEntity)
            _connectionState.value = ApiConnectionState.OFFLINE
            return updatedEntity.toDomain()
        }
    }

    /**
     * Sincronización invocada por el usuario desde la UI ("Sincronizar pendientes").
     */
    suspend fun sincronizarPendientes(): Int {
        return withContext(Dispatchers.IO) {
            syncMutex.withLock {
                _isSyncing.value = true
                var exitosos = 0

                try {
                    val pendientes = dao.getPendientes()
                    if (pendientes.isEmpty()) {
                        _lastSyncSummary.value = "No hay registros pendientes."
                        return@withLock 0
                    }

                    if (baseUrl.isBlank()) {
                        _lastSyncSummary.value = "No se puede sincronizar: URL de API sin configurar."
                        return@withLock 0
                    }

                    if (!sessionManager.isLoggedIn()) {
                        _lastSyncSummary.value = "Debe iniciar sesión para sincronizar ${pendientes.size} registros."
                        return@withLock 0
                    }

                    _lastSyncSummary.value = "Sincronizando ${pendientes.size} registros..."

                    for (item in pendientes) {
                        val result = enviarRegistroASigo(item)
                        if (result.estadoSincronizacion == EstadoSincronizacion.SINCRONIZADO) {
                            exitosos++
                        }
                    }

                    _lastSyncSummary.value = "Sincronización: $exitosos de ${pendientes.size} confirmados en SIGO."
                } catch (e: Exception) {
                    _lastSyncSummary.value = "Error en sincronización: ${e.localizedMessage}"
                } finally {
                    _isSyncing.value = false
                }

                exitosos
            }
        }
    }

    /**
     * Sincronización en segundo plano invocada por WorkManager.
     */
    suspend fun sincronizarPendientesDesdeWorker(): Boolean {
        return withContext(Dispatchers.IO) {
            syncMutex.withLock {
                if (!sessionManager.isLoggedIn() || baseUrl.isBlank()) {
                    return@withLock false
                }

                val pendientes = dao.getPendientes()
                if (pendientes.isEmpty()) return@withLock true

                var todosExitosos = true
                for (item in pendientes) {
                    val result = enviarRegistroASigo(item)
                    if (result.estadoSincronizacion != EstadoSincronizacion.SINCRONIZADO) {
                        todosExitosos = false
                    }
                }
                todosExitosos
            }
        }
    }

    /**
     * Consulta el historial remoto en el servidor SIGO.
     */
    suspend fun consultarHistorialServidor(
        desde: String? = null,
        hasta: String? = null,
        plazaId: String? = null,
        via: Int? = null,
        accion: String? = null
    ): Result<List<RegistroSigoResponse>> {
        return withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(Exception("URL del backend no configurada."))
            }

            try {
                val response = sigoApi.consultarRegistros(
                    desde = desde,
                    hasta = hasta,
                    plazaId = plazaId,
                    via = via,
                    accion = accion
                )
                if (response.isSuccessful && response.body() != null) {
                    _connectionState.value = ApiConnectionState.ONLINE
                    Result.success(response.body()!!)
                } else {
                    Result.failure(Exception("Error al consultar SIGO (${response.code()}): ${response.message()}"))
                }
            } catch (e: Throwable) {
                _connectionState.value = ApiConnectionState.OFFLINE
                Result.failure(Exception(clasificarErrorTecnico(e)))
            }
        }
    }

    suspend fun eliminarIncidencia(id: String) {
        withContext(Dispatchers.IO) {
            dao.delete(id)
        }
    }

    companion object {
        private const val TAG = "IncidentRepository"

        @Volatile
        private var INSTANCE: IncidentRepository? = null

        fun getInstance(context: Context): IncidentRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IncidentRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
