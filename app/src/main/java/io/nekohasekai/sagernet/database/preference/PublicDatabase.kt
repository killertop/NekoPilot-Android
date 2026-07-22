package io.nekohasekai.sagernet.database.preference

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

@Database(entities = [KeyValuePair::class], version = 1)
abstract class PublicDatabase : RoomDatabase() {
    companion object {
        val instance by lazy {
            Room.databaseBuilder(SagerNet.application, PublicDatabase::class.java, Key.DB_PUBLIC)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .enableMultiInstanceInvalidation()
                .setQueryExecutor(Dispatchers.IO.asExecutor())
                .build()
        }

        val kvPairDao get() = instance.keyValuePairDao()
    }

    abstract fun keyValuePairDao(): KeyValuePair.Dao

}
