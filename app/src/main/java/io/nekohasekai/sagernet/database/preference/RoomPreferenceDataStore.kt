package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.ktx.applicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(
    database: RoomDatabase,
    private val kvPairDao: KeyValuePair.Dao,
) :
    PreferenceDataStore() {

    private val cache = AtomicReference(loadAll())
    private val stateLock = Any()

    init {
        database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("KeyValuePair") {
            override fun onInvalidated(tables: Set<String>) {
                applicationScope.launch(Dispatchers.IO) { reloadFromDatabase() }
            }
        })
    }

    private fun loadAll() = runBlocking(Dispatchers.IO) {
        kvPairDao.all().associateBy(KeyValuePair::key)
    }

    private fun sameValue(left: KeyValuePair?, right: KeyValuePair?) = when {
        left === right -> true
        left == null || right == null -> false
        else -> left.valueType == right.valueType && left.value.contentEquals(right.value)
    }

    private fun reloadFromDatabase() {
        val updated = kvPairDao.all().associateBy(KeyValuePair::key)
        val previous = synchronized(stateLock) { cache.getAndSet(updated) }
        (previous.keys + updated.keys)
            .filter { !sameValue(previous[it], updated[it]) }
            .forEach(::fireChangeListener)
    }

    private fun putValue(key: String, value: KeyValuePair) {
        synchronized(stateLock) {
            val previous = cache.get()
            cache.set(previous + (key to value))
            try {
                runBlocking(Dispatchers.IO) { kvPairDao.put(value) }
            } catch (error: Throwable) {
                cache.set(previous)
                throw error
            }
        }
        fireChangeListener(key)
    }

    fun getBoolean(key: String) = cache.get()[key]?.boolean
    fun getFloat(key: String) = cache.get()[key]?.float
    fun getInt(key: String) = cache.get()[key]?.long?.toInt()
    fun getLong(key: String) = cache.get()[key]?.long
    fun getString(key: String) = cache.get()[key]?.string
    fun getStringSet(key: String) = cache.get()[key]?.stringSet
    fun reset() {
        val keys = synchronized(stateLock) {
            val previous = cache.get()
            runBlocking(Dispatchers.IO) { kvPairDao.reset() }
            cache.set(emptyMap())
            previous.keys
        }
        keys.forEach(::fireChangeListener)
    }

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValue: MutableSet<String>?) =
        getStringSet(key) ?: defValue

    fun putBoolean(key: String, value: Boolean?) =
        if (value == null) remove(key) else putBoolean(key, value)

    fun putFloat(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    fun putInt(key: String, value: Int?) =
        if (value == null) remove(key) else putLong(key, value.toLong())

    fun putLong(key: String, value: Long?) = if (value == null) remove(key) else putLong(key, value)
    override fun putBoolean(key: String, value: Boolean) {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putFloat(key: String, value: Float) {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putInt(key: String, value: Int) {
        putValue(key, KeyValuePair(key).put(value.toLong()))
    }

    override fun putLong(key: String, value: Long) {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putString(key: String, value: String?) = if (value == null) remove(key) else {
        putValue(key, KeyValuePair(key).put(value))
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        if (values == null) remove(key) else {
            putValue(key, KeyValuePair(key).put(values))
        }

    fun remove(key: String) {
        synchronized(stateLock) {
            val previous = cache.get()
            if (previous[key] == null) return
            cache.set(previous - key)
            try {
                runBlocking(Dispatchers.IO) { kvPairDao.delete(key) }
            } catch (error: Throwable) {
                cache.set(previous)
                throw error
            }
        }
        fireChangeListener(key)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) {
            listeners.toList()
        }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
}
