package com.sales.android.data

import androidx.room.Dao
import androidx.room.Query
import com.sales.android.model.OutboxEntity

@Dao
interface OutboxDao {

    @Query("SELECT * FROM outbox WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY timestamp ASC")
    suspend fun getPendingMutations(): List<OutboxEntity>

    @Query("UPDATE outbox SET status = :status WHERE id = :id")
    suspend fun updateMutationStatus(id: String, status: String)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteMutation(id: String)
}
