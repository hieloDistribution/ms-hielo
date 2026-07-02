package com.sales.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sales.android.model.OrderEntity
import com.sales.android.model.OrderItemEntity
import com.sales.android.model.OutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var orderDao: OrderDao
    private lateinit var outboxDao: OutboxDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Creamos la base de datos de Room en memoria para pruebas rapidas y aisladas
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        orderDao = db.orderDao()
        outboxDao = db.outboxDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testGuardadoAtomicoPedidoYOutbox() = runBlocking {
        val orderId = UUID.randomUUID().toString()
        val outboxId = UUID.randomUUID().toString()

        val order = OrderEntity(
            clientOrderId = orderId,
            clientId = "CLIENTE-001",
            salespersonId = "VENDEDOR-1",
            totalAmount = 240.0,
            createdAt = System.currentTimeMillis()
        )

        val items = listOf(
            OrderItemEntity(
                clientOrderId = orderId,
                productId = "PROD-ICE-001",
                quantity = 2,
                price = 120.0
            )
        )

        val outbox = OutboxEntity(
            id = outboxId,
            entityType = "ORDER",
            entityId = orderId,
            operation = "CREATE",
            payload = "{\"clientOrderId\":\"$orderId\",\"items\":[]}",
            timestamp = System.currentTimeMillis()
        )

        // Ejecutar transaccion atomica
        orderDao.saveOrderWithOutbox(order, items, outbox)

        // Verificar que las mutaciones pendientes existan
        val pending = outboxDao.getPendingMutations()
        assertEquals(1, pending.size)
        assertEquals(outboxId, pending[0].id)
        assertEquals("ORDER", pending[0].entityType)
        assertEquals(orderId, pending[0].entityId)
    }
}
