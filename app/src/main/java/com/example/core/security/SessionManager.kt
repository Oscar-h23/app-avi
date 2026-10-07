package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.model.LoginResponse
import com.example.model.UsuarioDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionState(
    val isLoggedIn: Boolean = false,
    val token: String? = null,
    val usuario: UsuarioDto? = null
)

class SessionManager(private val context: Context) {

    private val prefs: SharedPreferences = createEncryptedOrFallbackPrefs(context)

    private val _sessionState = MutableStateFlow(loadCurrentState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private fun loadCurrentState(): SessionState {
        val token = prefs.getString(KEY_TOKEN, null)
        val hasSession = !token.isNullOrBlank()
        val user = if (hasSession) {
            UsuarioDto(
                id = if (prefs.contains(KEY_USER_ID)) prefs.getLong(KEY_USER_ID, 0L) else null,
                codigo = if (prefs.contains(KEY_USER_CODIGO)) prefs.getInt(KEY_USER_CODIGO, 0) else null,
                nombre = prefs.getString(KEY_USER_NOMBRE, null),
                rol = prefs.getString(KEY_USER_ROL, "OPERADOR"),
                plazaId = if (prefs.contains(KEY_USER_PLAZA_ID)) prefs.getLong(KEY_USER_PLAZA_ID, 0L) else null,
                plaza = prefs.getString(KEY_USER_PLAZA, null)
            )
        } else null

        return SessionState(
            isLoggedIn = hasSession,
            token = token,
            usuario = user
        )
    }

    fun saveSession(loginResponse: LoginResponse) {
        val editor = prefs.edit()
        editor.putString(KEY_TOKEN, loginResponse.token)
        editor.putString(KEY_TIPO, loginResponse.tipo)
        loginResponse.expiresIn?.let { editor.putLong(KEY_EXPIRES_IN, it) }

        loginResponse.usuario?.let { u ->
            u.id?.let { editor.putLong(KEY_USER_ID, it) }
            u.codigo?.let { editor.putInt(KEY_USER_CODIGO, it) }
            u.nombre?.let { editor.putString(KEY_USER_NOMBRE, it) }
            u.rol?.let { editor.putString(KEY_USER_ROL, it) }
            u.plazaId?.let { editor.putLong(KEY_USER_PLAZA_ID, it) }
            u.plaza?.let { editor.putString(KEY_USER_PLAZA, it) }
        }
        editor.apply()

        _sessionState.value = loadCurrentState()
    }

    fun getToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }

    fun getUsuario(): UsuarioDto? {
        return _sessionState.value.usuario
    }

    fun isLoggedIn(): Boolean {
        return _sessionState.value.isLoggedIn
    }

    /**
     * Cierra la sesión activa eliminando el token de autenticación.
     * IMPORTANTE: No elimina los registros pendientes guardados en Room.
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_TIPO)
            .remove(KEY_EXPIRES_IN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_CODIGO)
            .remove(KEY_USER_NOMBRE)
            .remove(KEY_USER_ROL)
            .remove(KEY_USER_PLAZA_ID)
            .remove(KEY_USER_PLAZA)
            .apply()

        _sessionState.value = SessionState(isLoggedIn = false, token = null, usuario = null)
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_FILE_SECURE = "avi_keystore_secure_session"
        private const val PREFS_FILE_FALLBACK = "avi_fallback_session"

        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_TIPO = "jwt_tipo"
        private const val KEY_EXPIRES_IN = "jwt_expires_in"
        private const val KEY_USER_ID = "usuario_id"
        private const val KEY_USER_CODIGO = "usuario_codigo"
        private const val KEY_USER_NOMBRE = "usuario_nombre"
        private const val KEY_USER_ROL = "usuario_rol"
        private const val KEY_USER_PLAZA_ID = "usuario_plaza_id"
        private const val KEY_USER_PLAZA = "usuario_plaza"

        private fun createEncryptedOrFallbackPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE_SECURE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "Keystore no disponible o error en cifrado, usando fallback protegido: ${e.message}")
                context.getSharedPreferences(PREFS_FILE_FALLBACK, Context.MODE_PRIVATE)
            }
        }
    }
}
