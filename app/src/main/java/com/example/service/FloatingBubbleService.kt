package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.data.IncidentRepository
import com.example.parser.AviParser
import com.example.util.AviDateUtils
import com.example.voice.AviSpeechManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private lateinit var speechManager: AviSpeechManager
    private lateinit var repository: IncidentRepository

    // Estado para controlar si está en modo burbuja pequeña o panel flotante expandido
    private val isExpanded = MutableStateFlow(false)

    // Coordenadas flotantes
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private val serviceLifecycleOwner = CustomServiceLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.onCreate()

        speechManager = AviSpeechManager.getInstance(this)
        repository = IncidentRepository.getInstance(this)

        startForegroundNotification()
        setupFloatingOverlay()

        isRunning = true
    }

    private fun startForegroundNotification() {
        val channelId = "avi_floating_service"
        val channelName = "AVIX Asistente Operativo"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Burbuja flotante activa de AVIX para captura de incidencias"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AVIX activo sobre otras aplicaciones")
            .setContentText("Burbuja flotante lista para registrar incidencias por voz")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupFloatingOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)

            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFF38BDF8),
                        secondary = Color(0xFFF59E0B),
                        background = Color(0xFF0F172A),
                        surface = Color(0xFF1E293B)
                    )
                ) {
                    FloatingOverlayContent(
                        isExpandedFlow = isExpanded,
                        speechManager = speechManager,
                        repository = repository,
                        onExpandToggle = {
                            val newExpanded = !isExpanded.value
                            isExpanded.value = newExpanded
                            updateLayoutParamsForExpansion(newExpanded)
                        },
                        onClose = {
                            stopSelf()
                        },
                        onDrag = { dx, dy ->
                            windowLayoutParams?.let { params ->
                                params.x += dx.toInt()
                                params.y += dy.toInt()
                                windowManager?.updateViewLayout(overlayView, params)
                            }
                        }
                    )
                }
            }
        }

        try {
            windowManager?.addView(overlayView, windowLayoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Permiso de superposición necesario para AVIX", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun updateLayoutParamsForExpansion(expanded: Boolean) {
        windowLayoutParams?.let { params ->
            if (expanded) {
                // Permitir que el panel tome foco para escribir en campos de texto si es necesario
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
            } else {
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            windowManager?.updateViewLayout(overlayView, params)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        speechManager.destroy()
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
        }
        serviceLifecycleOwner.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }
}

/**
 * Contenido Compose renderizado dentro de la ventana de WindowManager
 */
@Composable
fun FloatingOverlayContent(
    isExpandedFlow: MutableStateFlow<Boolean>,
    speechManager: AviSpeechManager,
    repository: IncidentRepository,
    onExpandToggle: () -> Unit,
    onClose: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit
) {
    val expanded by isExpandedFlow.collectAsState()
    val voiceState by speechManager.voiceState.collectAsState()
    val scope = rememberCoroutineScope()

    var accionInput by remember { mutableStateOf("") }
    var viaInput by remember { mutableStateOf("") }
    var placaInput by remember { mutableStateOf("") }
    var textoOriginalInput by remember { mutableStateOf("") }
    var fechaHoraEventoCapturada by remember { mutableStateOf(AviDateUtils.nowLimaIso()) }
    var mensajeRegistro by remember { mutableStateOf<String?>(null) }

    // Conectar el resultado de voz con los campos
    DisposableEffect(speechManager) {
        val listener: (String) -> Unit = { textoReconocido ->
            fechaHoraEventoCapturada = AviDateUtils.nowLimaIso()
            val parsed = AviParser.parse(textoReconocido)
            accionInput = parsed.accion
            viaInput = parsed.via?.toString() ?: ""
            // No conservar una placa anterior: solo mostrar la placa detectada
            // después de que el dictado contenga explícitamente la palabra "placa".
            placaInput = parsed.placa
            textoOriginalInput = textoReconocido
        }
        speechManager.addResultListener(listener)
        onDispose {
            speechManager.removeResultListener(listener)
        }
    }

    if (!expanded) {
        // BURBUJA COLAPSADA
        Box(
            modifier = Modifier
                .wrapContentSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable { onExpandToggle() }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(62.dp)
                    .shadow(12.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF0F172A))
                    .border(2.5.dp, Color(0xFF38BDF8), CircleShape)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Abrir AVIX",
                        tint = if (voiceState.isListening) Color(0xFFEF4444) else Color(0xFF38BDF8),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "AVIX",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    } else {
        // PANEL EXPANDIDO FLOTANTE
        Card(
            modifier = Modifier
                .width(340.dp)
                .padding(4.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0F172A).copy(alpha = 0.96f)
            ),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Cabecera arrastrable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (voiceState.isListening) Color(0xFFEF4444) else Color(0xFF10B981))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AVIX - Asistente Flotante",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    Row {
                        IconButton(
                            onClick = onExpandToggle,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Minimizar",
                                tint = Color.LightGray
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = Color.LightGray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Guía breve del orden de dictado
                Text(
                    text = "ORDEN: [ACCIÓN] + [VÍA] + [PLACA]",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Captura principal por voz
                Button(
                    onClick = {
                        if (voiceState.isListening) {
                            speechManager.stopListening()
                        } else {
                            speechManager.startListening()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (voiceState.isListening) Color(0xFFDC2626) else Color(0xFF0284C7)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (voiceState.isListening) "DETENER ESCUCHA" else "PULSAR PARA HABLAR",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                if (voiceState.isListening) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Escuchando...",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (voiceState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "⚠ ${voiceState.errorMessage}",
                        color = Color(0xFFF87171),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Selector de Acción: FUGA vs DERIVADO
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = accionInput == "FUGA",
                        onClick = { accionInput = "FUGA" },
                        label = { Text("FUGA", fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFDC2626),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = accionInput == "DERIVADO",
                        onClick = { accionInput = "DERIVADO" },
                        label = { Text("DERIVADO", fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFD97706),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Campos interpretados (Placa y Vía)
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = placaInput,
                        onValueChange = { placaInput = it },
                        label = { Text("Placa", fontSize = 11.sp) },
                        modifier = Modifier.weight(1.2f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedTextField(
                        value = viaInput,
                        onValueChange = { viaInput = it },
                        label = { Text("Vía", fontSize = 11.sp) },
                        modifier = Modifier.weight(0.8f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Hora evento (Lima): ${AviDateUtils.formatIsoToDisplay(fechaHoraEventoCapturada)}",
                    fontSize = 10.sp,
                    color = Color.LightGray
                )

                if (textoOriginalInput.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Texto: \"$textoOriginalInput\"",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }

                if (mensajeRegistro != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = mensajeRegistro!!,
                        color = Color(0xFF10B981),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Botón REGISTRAR
                Button(
                    onClick = {
                        val viaNum = viaInput.toIntOrNull()
                        scope.launch {
                            repository.registrarIncidencia(
                                placa = placaInput.uppercase(),
                                via = viaNum,
                                accion = accionInput,
                                fechaHoraEvento = fechaHoraEventoCapturada,
                                textoReconocido = textoOriginalInput
                            )
                            mensajeRegistro = "✓ Registrado y enviado a SIGO"
                            kotlinx.coroutines.delay(1200)
                            mensajeRegistro = null
                            onExpandToggle() // Minimiza la burbuja automáticamente
                        }
                    },
                    enabled = placaInput.isNotBlank() &&
                            viaInput.toIntOrNull() != null &&
                            accionInput in listOf("FUGA", "DERIVADO"),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF059669)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "REGISTRAR EN SIGO",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * Provee un ciclo de vida para ComposeView cuando se ejecuta en un Service con WindowManager
 */
class CustomServiceLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
}
