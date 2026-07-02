package com.sales.android.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sales.android.data.AppDatabase
import com.sales.android.network.MutationRequest
import com.sales.android.network.SyncApiClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        
        // En producción esto debería inyectarse o leerse de una configuración dinámica
        private const val BASE_URL = "http://10.0.2.2:8081/" // IP por defecto de la PC host desde el emulador de Android
    }

    private val database = AppDatabase.getDatabase(context)
    private val orderDao = database.orderDao()
    private val outboxDao = database.outboxDao()

    private val apiClient: SyncApiClient by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SyncApiClient::class.java)
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Iniciando proceso de sincronización en segundo plano...")

        // 1. Verificar conectividad real a internet
        if (!ConnectivityMonitor.hasInternetAccess()) {
            Log.w(TAG, "Conexión a internet no disponible o inestable. Reintentando más tarde.")
            return Result.retry()
        }

        // 2. Leer las mutaciones pendientes
        val pendingMutations = outboxDao.getPendingMutations()
        if (pendingMutations.isEmpty()) {
            Log.i(TAG, "No hay mutaciones pendientes de sincronización.")
            return Result.success()
        }

        Log.i(TAG, "Se encontraron ${pendingMutations.size} mutaciones pendientes.")

        // 3. Mapear entidades a la estructura de red
        val requests = pendingMutations.map {
            MutationRequest(
                id = it.id,
                entityType = it.entityType,
                entityId = it.entityId,
                operation = it.operation,
                payload = it.payload,
                timestamp = it.timestamp
            )
        }

        try {
            // 4. Marcar temporalmente las mutaciones en Room como en proceso (evita ejecuciones duplicadas locales)
            pendingMutations.forEach {
                outboxDao.updateMutationStatus(it.id, "PROCESSING")
            }

            // 5. Enviar por Retrofit al backend
            val response = apiClient.syncMutations(requests)

            if (response.isSuccessful) {
                val syncResponse = response.body()
                if (syncResponse != null && syncResponse.success) {
                    val processedIds = syncResponse.processedMutationIds

                    // 6. Confirmación y purga local de datos procesados exitosamente
                    pendingMutations.forEach { mutation ->
                        if (processedIds.contains(mutation.id)) {
                            if (mutation.operation.equals("DELETE", ignoreCase = true)) {
                                // Eliminar físicamente el pedido y su registro de outbox
                                orderDao.purgeProcessedDelete(mutation.entityId, mutation.id)
                                Log.d(TAG, "Pedido borrado purgado localmente: ${mutation.entityId}")
                            } else {
                                // Eliminar registro del outbox (la orden queda guardada localmente)
                                outboxDao.deleteMutation(mutation.id)
                                Log.d(TAG, "Mutación de creación/actualización eliminada del Outbox: ${mutation.id}")
                            }
                        }
                    }
                    Log.i(TAG, "Lote sincronizado y purgado exitosamente.")
                    return Result.success()
                } else {
                    val errorMsg = syncResponse?.errorMessage ?: "Error desconocido del backend"
                    Log.e(TAG, "El servidor rechazó la sincronización: $errorMsg")
                    markMutationsAsFailed(pendingMutations.map { it.id })
                    return Result.failure()
                }
            } else {
                Log.e(TAG, "Error HTTP en la respuesta del servidor: ${response.code()}")
                markMutationsAsFailed(pendingMutations.map { it.id })
                return Result.retry() // Reintenta más tarde con el retroceso exponencial de WorkManager
            }

        } catch (e: Exception) {
            Log.e(TAG, "Excepción durante la sincronización: ${e.message}", e)
            markMutationsAsFailed(pendingMutations.map { it.id })
            return Result.retry()
        }
    }

    private suspend fun markMutationsAsFailed(ids: List<String>) {
        ids.forEach { id ->
            outboxDao.updateMutationStatus(id, "FAILED")
        }
    }
}
