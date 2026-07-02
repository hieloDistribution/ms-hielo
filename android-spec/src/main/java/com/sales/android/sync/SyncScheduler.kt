package com.sales.android.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val UNIQUE_WORK_NAME = "OfflineOrdersSyncWork"

    /**
     * Encola una tarea unica de sincronizacion de pedidos.
     * Se ejecuta de inmediato si se cumplen las restricciones de red.
     */
    fun enqueueOneTimeSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Solo con red disponible
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL, // Retroceso exponencial para reintentos con Jitter
                WorkRequest.MIN_BACKOFF_MILLIS, // 10 segundos por defecto de base
                TimeUnit.MILLISECONDS
            )
            .addTag("SYNC_ORDER_TAG")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP, // Conservar el trabajo pendiente existente, no duplicar la cola
            syncRequest
        )
    }

    /**
     * Encola una tarea periodica para asegurar que los pedidos se envien
     * regularmente incluso si el usuario no realiza ninguna accion manual.
     */
    fun enqueuePeriodicSync(context: Context, repeatIntervalMinutes: Long = 15) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicSyncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            repeatIntervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag("PERIODIC_SYNC_ORDER_TAG")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME + "_PERIODIC",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSyncRequest
        )
    }
}
