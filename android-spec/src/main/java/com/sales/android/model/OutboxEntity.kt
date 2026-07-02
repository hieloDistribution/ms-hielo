package com.sales.android.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey
    val id: String, // UUID de la mutacion generado en el cliente
    val entityType: String, // "ORDER"
    val entityId: String, // UUID del pedido afectado
    val operation: String, // "CREATE", "UPDATE", "DELETE"
    val payload: String, // El pedido completo en formato JSON (como String)
    val timestamp: Long, // System.currentTimeMillis()
    val status: String = "PENDING" // "PENDING", "PROCESSING", "FAILED"
)
