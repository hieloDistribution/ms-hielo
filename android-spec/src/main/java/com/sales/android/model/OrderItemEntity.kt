package com.sales.android.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "order_items",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["clientOrderId"],
            childColumns = ["clientOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["clientOrderId"])]
)
data class OrderItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val clientOrderId: String, // UUID del pedido padre
    val productId: String,
    val quantity: Int,
    val price: Double
)
