package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.ApiConnectionState
import com.example.data.IncidentRepository
import com.example.model.DiagnosticStage
import com.example.model.EstadoSincronizacion
import com.example.model.Incident
import com.example.model.ParsedCommand
import com.example.parser.AviParser
import com.example.service.FloatingBubbleService
import com.example.ui.theme.AviActionDerivado
import com.example.ui.theme.AviActionFuga
import com.example.ui.theme.AviBlueAccent
import com.example.ui.theme.AviNavy
import com.example.ui.theme.AviPrimaryDark
import com.example.ui.theme.AviStatusOffline
import com.example.ui.theme.AviStatusOnline
import com.example.ui.theme.AviStatusPending
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AviDateUtils
import com.example.voice.AviSpeechManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AviRootNavHost()
            }
        }
    }
}

enum class AviNavigationTab(val title: String) {
    INICIO("Inicio"),
    DICTADO("Dictado"),
    REVISION("Revisión"),
    HISTORIAL("Historial"),
    AJUSTES("Ajustes")
}

@Composable
fun AviRootNavHost() {
    val context = LocalContext.current
    val repository = remember { IncidentRepository.getInstance(context) }
    val sessionState by repository.sessionState.collectAsState()

    // Manejo de eventos de sesión expirada
    LaunchedEffect(Unit) {
        repository.sessionExpiredEvent.collect { mensaje ->
            Toast.makeText(context, mensaje, Toast.LENGTH_LONG).show()
        }
    }

    if (!sessionState.isLoggedIn) {
        AviLoginScreen(repository = repository)
    } else {
        AviMainDashboardScaffold(repository = repository)
    }
}

// =========================================================================
// PANTALLA 1: LOGIN (Autenticación por código de trabajador)
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AviLoginScreen(repository: IncidentRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var codigoInput by remember { mutableStateOf("2396") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var tempUrl by remember { mutableStateOf(repository.getBaseUrl()) }

    val connectionState by repository.connectionState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.avix_logo),
                contentDescription = "AVIX",
                modifier = Modifier.size(118.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Bienvenido",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Asistente operativo de voz",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Acceso",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Ingresa tu código de trabajador para continuar.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    OutlinedTextField(
                        value = codigoInput,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } && input.length <= 8) {
                                codigoInput = input
                                errorMessage = null
                            }
                        },
                        label = { Text("Código de usuario") },
                        placeholder = { Text("Ingresa tu código") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Badge,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("worker_code_input")
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFDECEC)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFD64545),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage!!,
                                    color = Color(0xFFD64545),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            val cod = codigoInput.toIntOrNull()
                            if (cod == null || cod <= 0) {
                                errorMessage = "Ingresa un código válido."
                                return@Button
                            }

                            isLoading = true
                            errorMessage = null

                            scope.launch {
                                val result = repository.login(cod)
                                isLoading = false
                                if (result.isFailure) {
                                    errorMessage = result.exceptionOrNull()?.message
                                        ?: "No se pudo iniciar sesión."
                                } else {
                                    Toast.makeText(context, "Bienvenido a AVIX", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isLoading && codigoInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("login_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Verificando...")
                        } else {
                            Text("Ingresar", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionState) {
                                    ApiConnectionState.ONLINE -> AviStatusOnline
                                    ApiConnectionState.OFFLINE -> AviStatusOffline
                                    ApiConnectionState.SIN_CONFIGURAR -> AviStatusPending
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (connectionState) {
                            ApiConnectionState.ONLINE -> "Servidor disponible"
                            ApiConnectionState.OFFLINE -> "Servidor sin conexión"
                            ApiConnectionState.SIN_CONFIGURAR -> "Servidor por configurar"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = { showUrlDialog = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Servidor", fontSize = 11.sp)
                }
            }
        }
    }

    if (showUrlDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Configurar servidor") },
            text = {
                Column {
                    Text(
                        "Selecciona una dirección o escribe la URL del backend.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
                        label = { Text("URL base") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { tempUrl = "http://172.20.10.8:8080/" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Mac local", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { tempUrl = "http://10.0.2.2:8080/" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Emulador", fontSize = 10.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.updateConfig(tempUrl)
                        repository.verificarConexionSigo()
                        showUrlDialog = false
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUrlDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

// =========================================================================
// PANTALLAS PRINCIPALES (SCAFFOLD POST-LOGIN)
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AviMainDashboardScaffold(repository: IncidentRepository) {
    val context = LocalContext.current
    val usuario = repository.getUsuarioActual()
    val connectionState by repository.connectionState.collectAsState()
    val pendientesCount by repository.pendientesCountFlow.collectAsState(initial = 0)
    val speechManager = remember { AviSpeechManager.getInstance(context) }

    var currentTab by remember { mutableStateOf(AviNavigationTab.INICIO) }

    // Estado del comando dictado o editado
    var currentParsedCommand by remember {
        mutableStateOf(
            ParsedCommand(
                placa = "",
                via = null,
                accion = "FUGA",
                textoOriginal = "",
                valido = false
            )
        )
    }
    var fechaHoraEventoCapturada by remember { mutableStateOf(AviDateUtils.nowLimaIso()) }

    // Comprobar permiso de overlay para la burbuja
    var hasOverlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        )
    }

    // BackHandler para navegación natural en Compose
    BackHandler(enabled = currentTab != AviNavigationTab.INICIO) {
        currentTab = AviNavigationTab.INICIO
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AVIX",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${usuario?.nombre ?: "Operador"} • ${usuario?.plaza ?: "Sin plaza"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Badge de conexión
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (connectionState) {
                            ApiConnectionState.ONLINE -> Color(0x33059669)
                            ApiConnectionState.OFFLINE -> Color(0x33DC2626)
                            ApiConnectionState.SIN_CONFIGURAR -> Color(0x33D97706)
                        },
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (connectionState) {
                                            ApiConnectionState.ONLINE -> AviStatusOnline
                                            ApiConnectionState.OFFLINE -> AviStatusOffline
                                            ApiConnectionState.SIN_CONFIGURAR -> AviStatusPending
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (connectionState) {
                                    ApiConnectionState.ONLINE -> "ONLINE"
                                    ApiConnectionState.OFFLINE -> "OFFLINE"
                                    ApiConnectionState.SIN_CONFIGURAR -> "CONFIG"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (connectionState) {
                                    ApiConnectionState.ONLINE -> AviStatusOnline
                                    ApiConnectionState.OFFLINE -> AviStatusOffline
                                    ApiConnectionState.SIN_CONFIGURAR -> AviStatusPending
                                }
                            )
                        }
                    }

                    // Acceso rápido a Burbuja flotante
                    IconButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                Toast.makeText(context, "Conceda permiso para superponer burbuja AVIX", Toast.LENGTH_LONG).show()
                            } else {
                                val intent = Intent(context, FloatingBubbleService::class.java)
                                if (FloatingBubbleService.isRunning) {
                                    context.stopService(intent)
                                    Toast.makeText(context, "Burbuja AVIX desactivada", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    Toast.makeText(context, "Burbuja flotante AVIX activada encima de otras apps", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "Burbuja Flotante",
                            tint = if (FloatingBubbleService.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = currentTab == AviNavigationTab.INICIO,
                    onClick = { currentTab = AviNavigationTab.INICIO },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Inicio") },
                    label = { Text("Inicio") }
                )
                NavigationBarItem(
                    selected = currentTab == AviNavigationTab.DICTADO,
                    onClick = { currentTab = AviNavigationTab.DICTADO },
                    icon = { Icon(Icons.Default.Mic, contentDescription = "Dictado") },
                    label = { Text("Hablar") }
                )
                NavigationBarItem(
                    selected = currentTab == AviNavigationTab.REVISION,
                    onClick = { currentTab = AviNavigationTab.REVISION },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Revisión") },
                    label = { Text("Revisión") }
                )
                NavigationBarItem(
                    selected = currentTab == AviNavigationTab.HISTORIAL,
                    onClick = { currentTab = AviNavigationTab.HISTORIAL },
                    icon = {
                        if (pendientesCount > 0) {
                            BadgedBox(badge = { Badge { Text("$pendientesCount") } }) {
                                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Historial")
                            }
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Historial")
                        }
                    },
                    label = { Text("Historial") }
                )
                NavigationBarItem(
                    selected = currentTab == AviNavigationTab.AJUSTES,
                    onClick = { currentTab = AviNavigationTab.AJUSTES },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Ajustes") },
                    label = { Text("Ajustes") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AviNavigationTab.INICIO -> {
                    PantallaInicio(
                        repository = repository,
                        pendientesCount = pendientesCount,
                        onIniciarHablar = {
                            fechaHoraEventoCapturada = AviDateUtils.nowLimaIso()
                            currentTab = AviNavigationTab.DICTADO
                        },
                        onRegistroManual = {
                            fechaHoraEventoCapturada = AviDateUtils.nowLimaIso()
                            currentParsedCommand = ParsedCommand(
                                placa = "",
                                via = 101,
                                accion = "FUGA",
                                textoOriginal = "Ingreso manual desde panel",
                                valido = false
                            )
                            currentTab = AviNavigationTab.REVISION
                        },
                        onSimularFrase = { frase ->
                            fechaHoraEventoCapturada = AviDateUtils.nowLimaIso()
                            val parsed = AviParser.parse(frase)
                            currentParsedCommand = parsed
                            currentTab = AviNavigationTab.REVISION
                        }
                    )
                }

                AviNavigationTab.DICTADO -> {
                    PantallaEscucha(
                        speechManager = speechManager,
                        onTextoFinalizado = { textoReconocido ->
                            val parsed = AviParser.parse(textoReconocido)
                            currentParsedCommand = parsed
                            currentTab = AviNavigationTab.REVISION
                        },
                        onCancelar = {
                            currentTab = AviNavigationTab.INICIO
                        }
                    )
                }

                AviNavigationTab.REVISION -> {
                    PantallaConfirmacion(
                        repository = repository,
                        parsedCommand = currentParsedCommand,
                        fechaHoraEvento = fechaHoraEventoCapturada,
                        onConfirmado = {
                            currentTab = AviNavigationTab.HISTORIAL
                        },
                        onCorregir = {
                            currentTab = AviNavigationTab.DICTADO
                        },
                        onCancelar = {
                            currentTab = AviNavigationTab.INICIO
                        }
                    )
                }

                AviNavigationTab.HISTORIAL -> {
                    PantallaHistorial(repository = repository)
                }

                AviNavigationTab.AJUSTES -> {
                    PantallaConfiguracion(repository = repository)
                }
            }
        }
    }
}

// =========================================================================
// PANTALLA 2: INICIO / DASHBOARD
// =========================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PantallaInicio(
    repository: IncidentRepository,
    pendientesCount: Int,
    onIniciarHablar: () -> Unit,
    onRegistroManual: () -> Unit,
    onSimularFrase: (String) -> Unit
) {
    val usuario = repository.getUsuarioActual()
    val connectionState by repository.connectionState.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Inicio",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Registro rápido de eventos operativos",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = usuario?.nombre ?: "Operador",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${usuario?.plaza ?: "Sin plaza"} • ${usuario?.rol ?: "OPERADOR"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when (connectionState) {
                        ApiConnectionState.ONLINE -> Color(0xFFEAF8F2)
                        ApiConnectionState.OFFLINE -> Color(0xFFFDECEC)
                        ApiConnectionState.SIN_CONFIGURAR -> Color(0xFFFFF7E6)
                    }
                ) {
                    Text(
                        text = when (connectionState) {
                            ApiConnectionState.ONLINE -> "Online"
                            ApiConnectionState.OFFLINE -> "Offline"
                            ApiConnectionState.SIN_CONFIGURAR -> "Config"
                        },
                        color = when (connectionState) {
                            ApiConnectionState.ONLINE -> AviStatusOnline
                            ApiConnectionState.OFFLINE -> AviStatusOffline
                            ApiConnectionState.SIN_CONFIGURAR -> AviStatusPending
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Nuevo registro",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onIniciarHablar,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Registrar por voz",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onRegistroManual,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Registro manual")
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sincronización",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = if (pendientesCount == 0)
                            "Todos los registros están sincronizados"
                        else
                            "$pendientesCount registro(s) pendiente(s)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (pendientesCount > 0) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repository.sincronizarPendientes()
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Sincronizar", fontSize = 11.sp)
                    }
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AviStatusOnline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// =========================================================================
// PANTALLA 3: ESCUCHA (Reconocimiento de Voz en tiempo real)
// =========================================================================
@Composable
fun PantallaEscucha(
    speechManager: AviSpeechManager,
    onTextoFinalizado: (String) -> Unit,
    onCancelar: () -> Unit
) {
    val context = LocalContext.current
    val voiceState by speechManager.voiceState.collectAsState()

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            speechManager.startListening()
        } else {
            Toast.makeText(
                context,
                "Permiso de micrófono requerido.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            speechManager.startListening()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(voiceState.stage, voiceState.recognizedText) {
        if (
            voiceState.stage == DiagnosticStage.STAGE_4 &&
            voiceState.recognizedText.isNotBlank()
        ) {
            kotlinx.coroutines.delay(350)
            onTextoFinalizado(voiceState.recognizedText)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (voiceState.isListening) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancelar) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver"
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = "Dictado por voz",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "Habla de forma clara y continua",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = "ACCIÓN  →  VÍA  →  PLACA",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(154.dp)
                .scale(pulseScale),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .size(130.dp)
                    .clickable {
                        if (voiceState.isListening) {
                            speechManager.stopListening()
                        } else {
                            speechManager.startListening()
                        }
                    },
                shape = CircleShape,
                color = if (voiceState.isListening)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (voiceState.isListening)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Micrófono",
                        tint = if (voiceState.isListening)
                            Color.White
                        else
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (voiceState.isListening)
                "Escuchando..."
            else
                "Pulsa el micrófono para hablar",
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )

        if (voiceState.recognizedText.isNotBlank()) {
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Texto reconocido",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = voiceState.recognizedText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (voiceState.errorMessage != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = voiceState.errorMessage ?: "",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onCancelar,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Cancelar")
        }
    }
}

// =========================================================================
// PANTALLA 4: CONFIRMACIÓN Y REVISIÓN (Editable antes de guardar)
// =========================================================================
@Composable
fun PantallaConfirmacion(
    repository: IncidentRepository,
    parsedCommand: ParsedCommand,
    fechaHoraEvento: String,
    onConfirmado: () -> Unit,
    onCorregir: () -> Unit,
    onCancelar: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var placaInput by remember { mutableStateOf(parsedCommand.placa) }
    var viaInput by remember { mutableStateOf(parsedCommand.via?.toString() ?: "") }
    var accionInput by remember { mutableStateOf(parsedCommand.accion) }
    var textoOriginalInput by remember { mutableStateOf(parsedCommand.textoOriginal) }

    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancelar) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "Confirmación de Incidencia",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Hora de evento: ${AviDateUtils.formatIsoToDisplay(fechaHoraEvento)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tarjeta con Texto Original
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "DICTADO ORIGINAL RECONOCIDO:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = textoOriginalInput.ifBlank { "(Entrada manual sin audio)" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // CAMPOS EDITABLES
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Datos Interpretados por AVIX",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AviNavy
                )
                Text(
                    text = "Revise y corrija los valores antes de confirmar",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Selección de Acción: FUGA o DERIVADO
                Text("Acción Operativa", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Botón FUGA
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (accionInput == "FUGA") AviActionFuga else Color(0xFFF1F5F9),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { accionInput = "FUGA" }
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "FUGA",
                                fontWeight = FontWeight.Bold,
                                color = if (accionInput == "FUGA") Color.White else Color(0xFF334155),
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Botón DERIVADO
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (accionInput == "DERIVADO") AviActionDerivado else Color(0xFFF1F5F9),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { accionInput = "DERIVADO" }
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "DERIVADO",
                                fontWeight = FontWeight.Bold,
                                color = if (accionInput == "DERIVADO") Color.White else Color(0xFF334155),
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Campo PLACA
                OutlinedTextField(
                    value = placaInput,
                    onValueChange = { placaInput = it.uppercase().replace(" ", "").replace("-", "") },
                    label = { Text("Placa del Vehículo") },
                    placeholder = { Text("Ej: BTL245") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("plate_input_field"),
                    trailingIcon = {
                        if (placaInput.length >= 6) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AviStatusOnline)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Campo VÍA
                OutlinedTextField(
                    value = viaInput,
                    onValueChange = { if (it.all { ch -> ch.isDigit() }) viaInput = it },
                    label = { Text("Número de Vía") },
                    placeholder = { Text("Ej: 101") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lane_input_field")
                )
            }
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0x33DC2626)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = errorMessage!!, color = Color(0xFFDC2626), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // BOTONES DE ACCIÓN
        Button(
            onClick = {
                val viaNum = viaInput.toIntOrNull()
                if (placaInput.isBlank()) {
                    errorMessage = "La placa no puede estar vacía."
                    return@Button
                }
                if (viaNum == null || viaNum <= 0) {
                    errorMessage = "Ingrese un número de vía válido."
                    return@Button
                }

                isSaving = true
                errorMessage = null
                scope.launch {
                    val resultado = repository.registrarIncidencia(
                        placa = placaInput,
                        via = viaNum,
                        accion = accionInput,
                        fechaHoraEvento = fechaHoraEvento,
                        textoReconocido = textoOriginalInput
                    )
                    isSaving = false
                    if (resultado.isSuccess) {
                        Toast.makeText(context, "Incidencia registrada con éxito.", Toast.LENGTH_SHORT).show()
                        onConfirmado()
                    } else {
                        errorMessage = resultado.exceptionOrNull()?.message ?: "Error al registrar incidencia."
                    }
                }
            },
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("confirm_register_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
        ) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Guardando...")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("CONFIRMAR Y REGISTRAR", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCorregir,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Volver a Hablar")
            }

            OutlinedButton(
                onClick = onCancelar,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Cancelar")
            }
        }
    }
}

// =========================================================================
// PANTALLA 5: HISTORIAL DE EVENTOS (Room Database + SIGO)
// =========================================================================
enum class FiltroHistorial(val label: String) {
    TODOS("Todos"),
    FUGAS("Fugas"),
    DERIVADOS("Derivados"),
    PENDIENTES("Pendientes")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaHistorial(repository: IncidentRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val incidents by repository.incidentsFlow.collectAsState(initial = emptyList())
    val isSyncing by repository.isSyncing.collectAsState()
    val lastSyncSummary by repository.lastSyncSummary.collectAsState()

    var filtroSeleccionado by remember { mutableStateOf(FiltroHistorial.TODOS) }

    val incidentesFiltrados = remember(incidents, filtroSeleccionado) {
        when (filtroSeleccionado) {
            FiltroHistorial.TODOS -> incidents
            FiltroHistorial.FUGAS -> incidents.filter { it.accion.equals("FUGA", ignoreCase = true) }
            FiltroHistorial.DERIVADOS -> incidents.filter { it.accion.equals("DERIVADO", ignoreCase = true) }
            FiltroHistorial.PENDIENTES -> incidents.filter { it.estadoSincronizacion != EstadoSincronizacion.SINCRONIZADO }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Encabezado con Botón de Sincronización
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Historial Operativo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Text(
                    text = "${incidents.size} eventos en base local",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        val cant = repository.sincronizarPendientes()
                        Toast.makeText(context, "Sincronizados: $cant", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isSyncing,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AviNavy)
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sincronizar", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Chips de Filtros
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (f in FiltroHistorial.entries) {
                FilterChip(
                    selected = filtroSeleccionado == f,
                    onClick = { filtroSeleccionado = f },
                    label = { Text(f.label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Lista de Incidencias
        if (incidentesFiltrados.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ListAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No hay registros para este filtro",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(incidentesFiltrados, key = { it.id }) { item ->
                    IncidenteCard(item = item, onDelete = {
                        scope.launch {
                            repository.eliminarIncidencia(item.id)
                        }
                    })
                }
            }
        }
    }
}

@Composable
fun IncidenteCard(item: Incident, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Placa grande
                Text(
                    text = item.placa,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = AviNavy,
                    letterSpacing = 1.sp
                )

                // Badge Acción: FUGA o DERIVADO
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (item.accion == "FUGA") Color(0x22DC2626) else Color(0x222563EB)
                ) {
                    Text(
                        text = item.accion,
                        fontWeight = FontWeight.Bold,
                        color = if (item.accion == "FUGA") AviActionFuga else AviActionDerivado,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Vía ${item.via ?: "---"} • Plaza ${item.plazaId}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )

                // Estado de sincronización
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (item.estadoSincronizacion) {
                        EstadoSincronizacion.SINCRONIZADO -> Color(0x22059669)
                        EstadoSincronizacion.PENDIENTE -> Color(0x22D97706)
                        EstadoSincronizacion.SINCRONIZANDO -> Color(0x222563EB)
                        EstadoSincronizacion.REQUIERE_REVISION -> Color(0x22DC2626)
                    }
                ) {
                    Text(
                        text = item.estadoSincronizacion.label,
                        color = when (item.estadoSincronizacion) {
                            EstadoSincronizacion.SINCRONIZADO -> AviStatusOnline
                            EstadoSincronizacion.PENDIENTE -> AviStatusPending
                            EstadoSincronizacion.SINCRONIZANDO -> AviBlueAccent
                            EstadoSincronizacion.REQUIERE_REVISION -> AviStatusOffline
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Evento: ${AviDateUtils.formatIsoToDisplay(item.fechaHoraEvento)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (item.textoReconocido.isNotBlank()) {
                Text(
                    text = "🗣 \"${item.textoReconocido}\"",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            if (item.ultimoError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${item.ultimoError}",
                    fontSize = 10.sp,
                    color = Color(0xFFDC2626)
                )
            }
        }
    }
}

// =========================================================================
// PANTALLA 6: CONFIGURACIÓN Y PERFIL DE OPERADOR
// =========================================================================
@Composable
fun PantallaConfiguracion(repository: IncidentRepository) {
    val context = LocalContext.current
    val usuario = repository.getUsuarioActual()
    val connectionState by repository.connectionState.collectAsState()

    var urlInput by remember { mutableStateOf(repository.getBaseUrl()) }
    var isChecking by remember { mutableStateOf(false) }

    var isBubbleRunning by remember { mutableStateOf(FloatingBubbleService.isRunning) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Configuración del Sistema",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Text(
            text = "Ajustes de conexión y servicio flotante",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Datos del Trabajador
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Sesión Activa", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "Trabajador: ${usuario?.nombre ?: "Operador"}", fontSize = 13.sp)
                Text(text = "Código de Operador: #${usuario?.codigo ?: "---"}", fontSize = 13.sp)
                Text(text = "Plaza Asignada: ${usuario?.plaza ?: "P4"}", fontSize = 13.sp)
                Text(text = "Rol: ${usuario?.rol ?: "OPERADOR"}", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Configuración de Backend REST
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Conexión a SIGO-BACK (Desarrollo)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = "Configure la IP de su Mac para emulador o celular físico",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("URL Base") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { urlInput = "http://10.0.2.2:8080/" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("10.0.2.2 (Emulador)", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { urlInput = "http://172.20.10.8:8080/" },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("172.20.10.8 (Mac)", fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        isChecking = true
                        repository.updateConfig(urlInput)
                        repository.verificarConexionSigo()
                        isChecking = false
                        Toast.makeText(context, "Configuración actualizada", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Guardar y Probar Conexión")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Burbuja Flotante Overlay
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Burbuja Flotante AVIX", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = "Permite dictar incidencias encima de la app de peaje de la empresa",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = isBubbleRunning,
                    onCheckedChange = { activate ->
                        if (activate) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } else {
                                val intent = Intent(context, FloatingBubbleService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                                isBubbleRunning = true
                            }
                        } else {
                            val intent = Intent(context, FloatingBubbleService::class.java)
                            context.stopService(intent)
                            isBubbleRunning = false
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Botón CERRAR SESIÓN
        Button(
            onClick = {
                repository.logout()
                Toast.makeText(context, "Sesión cerrada correctamente.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("CERRAR SESIÓN DE OPERADOR", fontWeight = FontWeight.Bold)
        }
    }
}
