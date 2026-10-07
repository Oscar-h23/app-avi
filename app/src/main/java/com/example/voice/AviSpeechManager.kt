package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.model.DiagnosticStage
import com.example.model.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gestor unificado de voz para AVI.
 * Implementa patrón Singleton para evitar conflictos de micrófono entre
 * MainActivity y FloatingBubbleService.
 */
class AviSpeechManager private constructor(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var simulationJob: Job? = null

    private var speechRecognizer: SpeechRecognizer? = null
    var isRecognizerAvailable = false
        private set

    private val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    // Lista de callbacks para notificar tanto a MainActivity como a la Burbuja
    private val resultListeners = mutableSetOf<(String) -> Unit>()

    init {
        mainHandler.post {
            initRecognizer()
        }
    }

    fun addResultListener(listener: (String) -> Unit) {
        resultListeners.add(listener)
    }

    fun removeResultListener(listener: (String) -> Unit) {
        resultListeners.remove(listener)
    }

    private fun initRecognizer() {
        try {
            isRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
            if (isRecognizerAvailable) {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                setupRecognitionListener()
            } else {
                _voiceState.value = _voiceState.value.copy(
                    errorMessage = "Servicio de reconocimiento de voz no disponible."
                )
            }
        } catch (e: Exception) {
            isRecognizerAvailable = false
            _voiceState.value = _voiceState.value.copy(
                errorMessage = "Error al iniciar reconocedor: ${e.localizedMessage}"
            )
        }
    }

    private fun setupRecognitionListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _voiceState.value = _voiceState.value.copy(
                    isListening = true,
                    stage = DiagnosticStage.STAGE_1,
                    stageDescription = "1/4 Micrófono abierto. Hable ahora...",
                    errorMessage = null
                )
            }

            override fun onBeginningOfSpeech() {
                _voiceState.value = _voiceState.value.copy(
                    stage = DiagnosticStage.STAGE_3,
                    stageDescription = "3/4 Voz detectada en el micrófono."
                )
            }

            override fun onRmsChanged(rmsdB: Float) {
                // RMS reportado nativamente por SpeechRecognizer (sin necesidad de bloquear con AudioRecord)
                val safeRms = (rmsdB + 2f).coerceAtLeast(0f)
                val currentStage = _voiceState.value.stage
                val newStage = if (safeRms > 0.5f && (currentStage == DiagnosticStage.STAGE_1 || currentStage == DiagnosticStage.IDLE)) {
                    DiagnosticStage.STAGE_2
                } else currentStage

                _voiceState.value = _voiceState.value.copy(
                    rmsLevel = safeRms,
                    stage = newStage,
                    stageDescription = if (newStage == DiagnosticStage.STAGE_2) "2/4 Señal de audio detectada (RMS: ${String.format("%.1f", safeRms)} dB)" else _voiceState.value.stageDescription
                )
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _voiceState.value = _voiceState.value.copy(
                    isListening = false,
                    stageDescription = "Procesando audio recibido..."
                )
            }

            override fun onError(error: Int) {
                handleSpeechError(error)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    _voiceState.value = _voiceState.value.copy(
                        isListening = false,
                        stage = DiagnosticStage.STAGE_4,
                        stageDescription = "4/4 Texto reconocido con éxito: \"$text\"",
                        recognizedText = text,
                        retryCount = 0,
                        errorMessage = null
                    )
                    notifyListeners(text)
                } else {
                    handleSpeechError(SpeechRecognizer.ERROR_NO_MATCH)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull() ?: ""
                if (partialText.isNotBlank()) {
                    _voiceState.value = _voiceState.value.copy(
                        recognizedText = partialText,
                        stage = DiagnosticStage.STAGE_3,
                        stageDescription = "3/4 Transcribiendo: $partialText"
                    )
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun notifyListeners(text: String) {
        resultListeners.forEach { listener ->
            try {
                listener(text)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleSpeechError(errorCode: Int) {
        val (message, canRetry) = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció ninguna frase de voz." to true
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de espera agotado sin detección de voz." to true
            SpeechRecognizer.ERROR_AUDIO -> "Error de captura de audio del micrófono." to false
            SpeechRecognizer.ERROR_CLIENT -> "El servicio de reconocimiento no respondió." to false
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permiso RECORD_AUDIO no concedido." to false
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Error de red en el servicio de voz." to false
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor de voz estaba ocupado. Reiniciando..." to true
            SpeechRecognizer.ERROR_SERVER -> "Error en el servidor de reconocimiento." to false
            else -> "Error en el reconocimiento de voz (código $errorCode)." to false
        }

        val currentRetries = _voiceState.value.retryCount
        if (canRetry && currentRetries < 1) {
            _voiceState.value = _voiceState.value.copy(
                isListening = false,
                stageDescription = "Reintentando automáticamente...",
                retryCount = currentRetries + 1,
                errorMessage = message
            )
            mainHandler.postDelayed({
                startListening()
            }, 300)
        } else {
            _voiceState.value = _voiceState.value.copy(
                isListening = false,
                stageDescription = "Sin reconocimiento activo.",
                errorMessage = message
            )
        }
    }

    fun startListening() {
        simulationJob?.cancel()

        // Verificar permisos en tiempo de ejecución
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _voiceState.value = _voiceState.value.copy(
                isListening = false,
                errorMessage = "Permiso de micrófono (RECORD_AUDIO) no otorgado."
            )
            return
        }

        mainHandler.post {
            try {
                // Cancelar cualquier sesión previa para limpiar buffers
                speechRecognizer?.cancel()

                if (speechRecognizer == null) {
                    initRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-PE")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "es-PE")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                }

                _voiceState.value = _voiceState.value.copy(
                    isListening = true,
                    stage = DiagnosticStage.STAGE_1,
                    stageDescription = "1/4 Micrófono abierto. Esperando tu voz...",
                    errorMessage = null
                )

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _voiceState.value = _voiceState.value.copy(
                    isListening = false,
                    errorMessage = "Error al iniciar escucha: ${e.localizedMessage}"
                )
            }
        }
    }

    fun stopListening() {
        simulationJob?.cancel()
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            _voiceState.value = _voiceState.value.copy(isListening = false)
        }
    }

    fun simulateVoiceInput(phrase: String) {
        simulationJob?.cancel()
        simulationJob = scope.launch {
            _voiceState.value = VoiceState(
                isListening = true,
                stage = DiagnosticStage.STAGE_1,
                stageDescription = "1/4 Micrófono abierto (Canal activo)",
                errorMessage = null,
                retryCount = 0
            )
            delay(300)

            _voiceState.value = _voiceState.value.copy(
                stage = DiagnosticStage.STAGE_2,
                rmsLevel = 6.2f,
                stageDescription = "2/4 Audio detectado (Señal recibida)"
            )
            delay(350)

            _voiceState.value = _voiceState.value.copy(
                stage = DiagnosticStage.STAGE_3,
                rmsLevel = 8.5f,
                stageDescription = "3/4 Voz detectada: \"$phrase\""
            )
            delay(400)

            _voiceState.value = _voiceState.value.copy(
                isListening = false,
                stage = DiagnosticStage.STAGE_4,
                rmsLevel = 0f,
                stageDescription = "4/4 Texto reconocido con éxito",
                recognizedText = phrase
            )
            notifyListeners(phrase)
        }
    }

    fun destroy() {
        simulationJob?.cancel()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    companion object {
        @Volatile
        private var instance: AviSpeechManager? = null

        fun getInstance(context: Context): AviSpeechManager {
            return instance ?: synchronized(this) {
                instance ?: AviSpeechManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
