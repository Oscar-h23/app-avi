package com.example.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Captura PCM orientada a voz para ambientes ruidosos.
 *
 * Usa VOICE_RECOGNITION para permitir que el fabricante aplique su cadena DSP
 * y además intenta habilitar NoiseSuppressor sobre la misma sesión de AudioRecord.
 *
 * El audio procesado se escribe a un pipe para poder inyectarlo a SpeechRecognizer
 * mediante RecognizerIntent.EXTRA_AUDIO_SOURCE en Android 13+.
 */
class NoiseReducedAudioSource {

    data class Session(
        val readDescriptor: ParcelFileDescriptor,
        val noiseSuppressorEnabled: Boolean,
        val sampleRate: Int = SAMPLE_RATE,
        val channelCount: Int = 1,
        val encoding: Int = AudioFormat.ENCODING_PCM_16BIT
    )

    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var readSide: ParcelFileDescriptor? = null
    private var writeSide: ParcelFileDescriptor? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Session? {
        release()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) return null

        val bufferSize = max(minBuffer * 2, 4096)

        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (_: Throwable) {
            return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        val suppressor = try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: Throwable) {
            suppressor?.release()
            recorder.release()
            return null
        }

        audioRecord = recorder
        noiseSuppressor = suppressor
        readSide = pipe[0]
        writeSide = pipe[1]

        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                release()
                null
            } else {
                running.set(true)
                val localWrite = pipe[1]
                val localRecorder = recorder

                executor = Executors.newSingleThreadExecutor().also { worker ->
                    worker.execute {
                        val buffer = ByteArray(bufferSize)

                        try {
                            FileOutputStream(localWrite.fileDescriptor).use { output ->
                                while (running.get()) {
                                    val read = localRecorder.read(
                                        buffer,
                                        0,
                                        buffer.size,
                                        AudioRecord.READ_BLOCKING
                                    )

                                    if (read > 0) {
                                        output.write(buffer, 0, read)
                                    } else if (read < 0) {
                                        break
                                    }
                                }
                                output.flush()
                            }
                        } catch (_: Throwable) {
                            // El pipe se cierra deliberadamente al detener la sesión.
                        }
                    }
                }

                Session(
                    readDescriptor = pipe[0],
                    noiseSuppressorEnabled = suppressor?.enabled == true
                )
            }
        } catch (_: Throwable) {
            release()
            null
        }
    }

    /**
     * Detiene el micrófono y cierra el extremo escritor del pipe para señalar EOF
     * al recognizer. El descriptor lector se conserva hasta release().
     */
    @Synchronized
    fun finishInput() {
        running.set(false)

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }

        try {
            writeSide?.close()
        } catch (_: Throwable) {
        }
        writeSide = null
    }

    @Synchronized
    fun release() {
        running.set(false)

        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }

        try {
            writeSide?.close()
        } catch (_: Throwable) {
        }

        try {
            readSide?.close()
        } catch (_: Throwable) {
        }

        try {
            noiseSuppressor?.release()
        } catch (_: Throwable) {
        }

        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }

        executor?.shutdownNow()

        executor = null
        audioRecord = null
        noiseSuppressor = null
        readSide = null
        writeSide = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
