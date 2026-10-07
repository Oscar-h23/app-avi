package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.IncidentRepository
import java.util.concurrent.TimeUnit

class SyncRegistroWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Iniciando SyncRegistroWorker en segundo plano...")

        val repository = IncidentRepository.getInstance(applicationContext)

        return try {
            val exito = repository.sincronizarPendientesDesdeWorker()
            if (exito) {
                Log.d(TAG, "SyncRegistroWorker finalizado con éxito.")
                Result.success()
            } else {
                Log.w(TAG, "SyncRegistroWorker encontró fallos temporales. Programando reintento.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en SyncRegistroWorker: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncRegistroWorker"
        private const val UNIQUE_ONE_TIME_WORK = "avi_sync_pending_one_time"
        private const val UNIQUE_PERIODIC_WORK = "avi_sync_periodic"

        /**
         * Programa una sincronización inmediata cuando haya conectividad a internet.
         */
        fun enqueueImmediateSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val syncRequest = OneTimeWorkRequestBuilder<SyncRegistroWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        15,
                        TimeUnit.SECONDS
                    )
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_ONE_TIME_WORK,
                    ExistingWorkPolicy.REPLACE,
                    syncRequest
                )
            } catch (e: Exception) {
                Log.w(TAG, "WorkManager no disponible para sincronización inmediata: ${e.message}")
            }
        }

        /**
         * Programa una sincronización periódica cada 15 minutos en segundo plano si hay red.
         */
        fun schedulePeriodicSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val periodicRequest = PeriodicWorkRequestBuilder<SyncRegistroWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                    )
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
            } catch (e: Exception) {
                Log.w(TAG, "WorkManager no disponible para sincronización periódica: ${e.message}")
            }
        }
    }
}
