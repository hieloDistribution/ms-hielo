package com.sales.android.data

import androidx.room.*
import com.sales.android.model.OrderEntity
import com.sales.android.model.OrderItemEntity
import com.sales.android.model.OutboxEntity

@Dao
interface OrderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItems(items: List<OrderItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(outbox: OutboxEntity)

    @Query("UPDATE orders SET isDeleted = 1 WHERE clientOrderId = :orderId")
    suspend fun softDeleteOrder(orderId: String)

    @Query("DELETE FROM orders WHERE clientOrderId = :orderId")
    suspend fun hardDeleteOrder(orderId: String)

    /**
     * Guarda o actualiza un pedido localmente y registra la mutacion en la tabla Outbox
     * bajo una unica transaccion atomica de base de datos.
     */
    @Transaction
    suspend fun saveOrderWithOutbox(order: OrderEntity, items: List<OrderItemEntity>, outbox: OutboxEntity) {
        insertOrder(order)
        insertOrderItems(items)
        insertOutbox(outbox)
    }

    /**
     * Marca un pedido para eliminacion blanda (Soft Delete) y registra la mutacion en la tabla Outbox
     * bajo una unica transaccion atomica.
     */
    @Transaction
    suspend fun deleteOrderWithOutbox(orderId: String, outbox: OutboxEntity) {
        softDeleteOrder(orderId)
        insertOutbox(outbox)
    }

    /**
     * Purga definitiva: elimina fisicamente el pedido (si fue marcado como borrado) y limpia su
     * registro correspondiente en el outbox tras confirmacion del backend.
     */
    @Transaction
    suspend fun purgeProcessedDelete(orderId: String, outboxId: String) {
        hardDeleteOrder(orderId)
        deleteOutboxById(outboxId)
    }

    @Query("DELETE FROM outbox WHERE id = :outboxId")
    suspend fun deleteOutboxById(outboxId: String)
}
