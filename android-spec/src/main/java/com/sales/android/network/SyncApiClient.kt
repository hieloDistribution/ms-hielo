package com.sales.android.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class MutationRequest(
    val id: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val timestamp: Long
)

data class SyncResponse(
    val success: Boolean,
    val processedMutationIds: List<String>,
    val errorMessage: String?
)

interface SyncApiClient {

    @POST("api/v1/sync")
    suspend fun syncMutations(
        @Body mutations: List<MutationRequest>
    ): Response<SyncResponse>
}
