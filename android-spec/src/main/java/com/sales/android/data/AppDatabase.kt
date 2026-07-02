package com.sales.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sales.android.model.OrderEntity
import com.sales.android.model.OrderItemEntity
import com.sales.android.model.OutboxEntity

@Database(
    entities = [OrderEntity::class, OrderItemEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun orderDao(): OrderDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "orders_offline_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
