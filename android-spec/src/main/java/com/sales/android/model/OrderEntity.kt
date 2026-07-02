package com.sales.android.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey
    val clientOrderId: String, // UUID generado en el cliente
    val clientId: String,
    val salespersonId: String,
    val totalAmount: Double, // Almacenado como Double en SQLite (o String usando TypeConverter)
    val createdAt: Long, // Timestamp en milisegundos
    val isDeleted: Boolean = false // Columna para Soft Delete
)
