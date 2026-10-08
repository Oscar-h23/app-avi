package com.example.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.model.DiagnosticStage
import com.example.model.ParsedCommand
import com.example.model.VoiceState
import com.example.parser.AviParser
import com.example.parser.AviCommandStage
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
data class VoiceMetricsSnapshot(
    val attempts: Int,
    val validFirstPass: Int,
    val invalidResults: Int,
    val alternativeSelected: Int,
    val missingAction: Int,
    val missingVia: Int,
    val missingPlate: Int,
    val manualCorrections: Int,
    val plateCorrections: Int,
    val viaCorrections: Int,
    val actionCorrections: Int
)

class AviSpeechManager private constructor(private val appContext: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var simulationJob: Job? = null

    private var allowedVias: Set<Int> = emptySet()
    private val metricsPrefs = appContext.getSharedPreferences(
        "avix_voice_metrics",
        Context.MODE_PRIVATE
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private val noiseReducedAudioSource = NoiseReducedAudioSource()
    private var enhancedAudioActive = false
    private var enhancedFallbackAttempted = false
    private var captureTimeoutRunnable: Runnable? = null

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

    fun setAllowedVias(vias: Set<Int>) {
        allowedVias = vias.filter { it > 0 }.toSet()
    }

    fun getVoiceMetrics(): VoiceMetricsSnapshot {
        return VoiceMetricsSnapshot(
            attempts = metricsPrefs.getInt(KEY_ATTEMPTS, 0),
            validFirstPass = metricsPrefs.getInt(KEY_VALID_FIRST_PASS, 0),
            invalidResults = metricsPrefs.getInt(KEY_INVALID_RESULTS, 0),
            alternativeSelected = metricsPrefs.getInt(KEY_ALTERNATIVE_SELECTED, 0),
            missingAction = metricsPrefs.getInt(KEY_MISSING_ACTION, 0),
            missingVia = metricsPrefs.getInt(KEY_MISSING_VIA, 0),
            missingPlate = metricsPrefs.getInt(KEY_MISSING_PLATE, 0),
            manualCorrections = metricsPrefs.getInt(KEY_MANUAL_CORRECTIONS, 0),
            plateCorrections = metricsPrefs.getInt(KEY_PLATE_CORRECTIONS, 0),
            viaCorrections = metricsPrefs.getInt(KEY_VIA_CORRECTIONS, 0),
            actionCorrections = metricsPrefs.getInt(KEY_ACTION_CORRECTIONS, 0)
        )
    }

    fun recordManualCorrection(
        original: ParsedCommand,
        finalPlate: String,
        finalVia: Int?,
        finalAction: String
    ) {
        val normalizedPlate = finalPlate.uppercase().replace("-", "").replace(" ", "")
        val plateChanged = original.placa != normalizedPlate
        val viaChanged = original.via != finalVia
        val actionChanged = !original.accion.equals(finalAction, ignoreCase = true)

        if (!plateChanged && !viaChanged && !actionChanged) return

        incrementMetric(KEY_MANUAL_CORRECTIONS)
        if (plateChanged) incrementMetric(KEY_PLATE_CORRECTIONS)
        if (viaChanged) incrementMetric(KEY_VIA_CORRECTIONS)
        if (actionChanged) incrementMetric(KEY_ACTION_CORRECTIONS)
    }

    private fun incrementMetric(key: String) {
        metricsPrefs.edit()
            .putInt(key, metricsPrefs.getInt(key, 0) + 1)
            .apply()
    }

    private fun recordRecognition(
        matches: List<String>,
        selected: String
    ) {
        incrementMetric(KEY_ATTEMPTS)

        val selectedIndex = matches.indexOf(selected)
        if (selectedIndex > 0) {
            incrementMetric(KEY_ALTERNATIVE_SELECTED)
        }

        val analysis = AviParser.analyzeCommand(selected, allowedVias)
        if (analysis.parsed.valido) {
            incrementMetric(KEY_VALID_FIRST_PASS)
        } else {
            incrementMetric(KEY_INVALID_RESULTS)
            when (analysis.stage) {
                AviCommandStage.ACCION -> incrementMetric(KEY_MISSING_ACTION)
                AviCommandStage.VIA -> incrementMetric(KEY_MISSING_VIA)
                AviCommandStage.PLACA -> incrementMetric(KEY_MISSING_PLATE)
                AviCommandStage.COMPLETO -> Unit
            }
        }
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
                    stageDescription = "1/4 Diga: acción, vía y placa.",
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
                if (enhancedAudioActive) {
                    noiseReducedAudioSource.finishInput()
                }

                _voiceState.value = _voiceState.value.copy(
                    isListening = false,
                    stageDescription = "Procesando audio recibido..."
                )
            }

            override fun onError(error: Int) {
                handleSpeechError(error)
            }

            override fun onResults(results: Bundle?) {
                cleanupEnhancedAudio()

                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                val confidenceScores = results
                    ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                val text = AviParser.selectBestHypothesis(
                    candidates = matches,
                    confidenceScores = confidenceScores,
                    allowedVias = allowedVias
                )

                if (text.isNotBlank()) {
                    val analysis = AviParser.analyzeCommand(text, allowedVias)
                    recordRecognition(matches, text)

                    _voiceState.value = _voiceState.value.copy(
                        isListening = false,
                        stage = DiagnosticStage.STAGE_4,
                        stageDescription = if (analysis.parsed.valido) {
                            "4/4 Comando completo y validado."
                        } else {
                            analysis.prompt
                        },
                        recognizedText = text,
                        retryCount = 0,
                        errorMessage = if (analysis.parsed.valido) {
                            null
                        } else {
                            analysis.prompt
                        }
                    )
                    notifyListeners(text)
                } else {
                    handleSpeechError(SpeechRecognizer.ERROR_NO_MATCH)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                val partialText = AviParser.selectBestHypothesis(
                    candidates = matches,
                    allowedVias = allowedVias
                )

                if (partialText.isNotBlank()) {
                    _voiceState.value = _voiceState.value.copy(
                        recognizedText = partialText,
                        stage = DiagnosticStage.STAGE_3,
                        stageDescription = "3/4 Interpretando comando..."
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
        val wasEnhanced = enhancedAudioActive
        cleanupEnhancedAudio()

        // Algunos RecognitionService no admiten EXTRA_AUDIO_SOURCE. Si el modo
        // reforzado provoca un error de captura/cliente, volver automáticamente
        // al micrófono administrado por SpeechRecognizer.
        if (
            wasEnhanced &&
            !enhancedFallbackAttempted &&
            errorCode in setOf(
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_CLIENT
            )
        ) {
            enhancedFallbackAttempted = true

            _voiceState.value = _voiceState.value.copy(
                isListening = false,
                stageDescription = "El dispositivo no aceptó audio procesado. Usando modo compatible...",
                errorMessage = null
            )

            mainHandler.postDelayed({
                startListeningInternal(preferEnhancedAudio = false)
            }, 250)
            return
        }

        if (errorCode != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            incrementMetric(KEY_ATTEMPTS)
            incrementMetric(KEY_INVALID_RESULTS)
        }

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
                startListeningInternal(preferEnhancedAudio = !enhancedFallbackAttempted)
            }, 300)
        } else {
            _voiceState.value = _voiceState.value.copy(
                isListening = false,
                stageDescription = "Sin reconocimiento activo.",
                errorMessage = message
            )
        }
    }

    private fun cleanupEnhancedAudio() {
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null
        noiseReducedAudioSource.release()
        enhancedAudioActive = false
    }

    fun startListening() {
        enhancedFallbackAttempted = false
        startListeningInternal(preferEnhancedAudio = true)
    }

    private fun startListeningInternal(
        preferEnhancedAudio: Boolean
    ) {
        simulationJob?.cancel()

        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _voiceState.value = _voiceState.value.copy(
                isListening = false,
                errorMessage = "Permiso de micrófono (RECORD_AUDIO) no otorgado."
            )
            return
        }

        mainHandler.post {
            try {
                cleanupEnhancedAudio()

                // Cancelar cualquier sesión previa para limpiar buffers.
                speechRecognizer?.cancel()

                if (speechRecognizer == null) {
                    initRecognizer()
                }

                val enhancedSession = if (
                    preferEnhancedAudio &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ) {
                    noiseReducedAudioSource.start()
                } else {
                    null
                }

                enhancedAudioActive = enhancedSession != null

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-PE")
                    putExtra(
                        RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE,
                        "es-PE"
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putStringArrayListExtra(
                            RecognizerIntent.EXTRA_BIASING_STRINGS,
                            ArrayList(
                                AviParser.recognitionBiasingPhrases(
                                    allowedVias = allowedVias
                                )
                            )
                        )

                        enhancedSession?.let { session ->
                            putExtra(
                                RecognizerIntent.EXTRA_AUDIO_SOURCE,
                                session.readDescriptor
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
                                session.channelCount
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            putExtra(
                                RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                                session.sampleRate
                            )
                        }
                    }

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                        1200L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        700L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        1100L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_CALLING_PACKAGE,
                        appContext.packageName
                    )
                }

                val audioModeText = if (enhancedSession != null) {
                    if (enhancedSession.noiseSuppressorEnabled) {
                        "Captura reforzada + reducción de ruido activa."
                    } else {
                        "Captura reforzada para voz activa."
                    }
                } else {
                    "Captura estándar del dispositivo."
                }

                _voiceState.value = _voiceState.value.copy(
                    isListening = true,
                    stage = DiagnosticStage.STAGE_1,
                    stageDescription = "1/4 $audioModeText Diga: acción, vía y placa.",
                    recognizedText = "",
                    rmsLevel = 0f,
                    errorMessage = null
                )

                speechRecognizer?.startListening(intent)

                // Límite de seguridad. Cerrar el pipe evita sesiones colgadas en
                // implementaciones que esperan EOF del EXTRA_AUDIO_SOURCE.
                if (enhancedSession != null) {
                    val timeout = Runnable {
                        if (enhancedAudioActive) {
                            noiseReducedAudioSource.finishInput()
                        }
                    }
                    captureTimeoutRunnable = timeout
                    mainHandler.postDelayed(timeout, ENHANCED_AUDIO_MAX_DURATION_MS)
                }
            } catch (e: Exception) {
                cleanupEnhancedAudio()
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
            if (enhancedAudioActive) {
                noiseReducedAudioSource.finishInput()
            }

            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
            }

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
            cleanupEnhancedAudio()
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null
        }
    }

    companion object {
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_VALID_FIRST_PASS = "valid_first_pass"
        private const val KEY_INVALID_RESULTS = "invalid_results"
        private const val KEY_ALTERNATIVE_SELECTED = "alternative_selected"
        private const val KEY_MISSING_ACTION = "missing_action"
        private const val KEY_MISSING_VIA = "missing_via"
        private const val KEY_MISSING_PLATE = "missing_plate"
        private const val KEY_MANUAL_CORRECTIONS = "manual_corrections"
        private const val KEY_PLATE_CORRECTIONS = "plate_corrections"
        private const val KEY_VIA_CORRECTIONS = "via_corrections"
        private const val KEY_ACTION_CORRECTIONS = "action_corrections"
        private const val ENHANCED_AUDIO_MAX_DURATION_MS = 8_000L

        @Volatile
        private var instance: AviSpeechManager? = null

        fun getInstance(context: Context): AviSpeechManager {
            return instance ?: synchronized(this) {
                instance ?: AviSpeechManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
