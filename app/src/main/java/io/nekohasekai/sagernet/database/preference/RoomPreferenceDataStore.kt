package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.ktx.applicationScope
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(
    database: RoomDatabase,
    private val kvPairDao: KeyValuePair.Dao,
) :
    PreferenceDataStore() {

    private val cache = AtomicReference(loadAll())
    private val stateLock = Any()
    private val localGeneration = AtomicLong()
    private val pendingMutations = AtomicInteger()
    private val lastWriteError = AtomicReference<Throwable?>()
    private val mutations = Channel<DatabaseMutation>(Channel.UNLIMITED)
    private val databaseObserver = object : InvalidationTracker.Observer("KeyValuePair") {
        override fun onInvalidated(tables: Set<String>) {
            if (pendingMutations.get() == 0) {
                applicationScope.launch(Dispatchers.IO) { reloadFromDatabase() }
            }
        }
    }

    private sealed interface DatabaseMutation {
        data class Put(val value: KeyValuePair) : DatabaseMutation
        data class Delete(val key: String) : DatabaseMutation
        data object Reset : DatabaseMutation
        data class Barrier(val completion: CompletableDeferred<Unit>) : DatabaseMutation
    }

    init {
        applicationScope.launch(Dispatchers.IO) {
            for (mutation in mutations) {
                try {
                    when (mutation) {
                        is DatabaseMutation.Put -> kvPairDao.put(mutation.value)
                        is DatabaseMutation.Delete -> kvPairDao.delete(mutation.key)
                        DatabaseMutation.Reset -> kvPairDao.reset()
                        is DatabaseMutation.Barrier -> Unit
                    }
                } catch (error: Throwable) {
                    lastWriteError.set(error)
                    Logs.e(error)
                } finally {
                    if (pendingMutations.decrementAndGet() == 0) {
                        try {
                            reloadFromDatabase()
                        } catch (error: Throwable) {
                            lastWriteError.set(error)
                            Logs.e(error)
                        }
                    }
                    if (mutation is DatabaseMutation.Barrier) {
                        lastWriteError.getAndSet(null)?.let(mutation.completion::completeExceptionally)
                            ?: mutation.completion.complete(Unit)
                    }
                }
            }
        }
        applicationScope.launch(Dispatchers.IO) {
            try {
                // Registering Room's multi-process invalidation observer can create triggers and
                // write the WAL. Do it off the first UI frame, then reload once to close the
                // small registration window without losing an external process update.
                database.invalidationTracker.addObserver(databaseObserver)
                reloadFromDatabase()
            } catch (error: Throwable) {
                lastWriteError.set(error)
                Logs.e(error)
            }
        }
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
        val generation = localGeneration.get()
        val updated = kvPairDao.all().associateBy(KeyValuePair::key)
        if (pendingMutations.get() != 0 || generation != localGeneration.get()) return
        val previous = synchronized(stateLock) { cache.getAndSet(updated) }
        (previous.keys + updated.keys)
            .filter { !sameValue(previous[it], updated[it]) }
            .forEach(::fireChangeListener)
    }

    private fun putValue(key: String, value: KeyValuePair) {
        synchronized(stateLock) {
            val previous = cache.get()
            cache.set(previous + (key to value))
            localGeneration.incrementAndGet()
            enqueue(DatabaseMutation.Put(value))
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
            cache.set(emptyMap())
            localGeneration.incrementAndGet()
            enqueue(DatabaseMutation.Reset)
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
            localGeneration.incrementAndGet()
            enqueue(DatabaseMutation.Delete(key))
        }
        fireChangeListener(key)
    }

    private fun enqueue(mutation: DatabaseMutation) {
        pendingMutations.incrementAndGet()
        if (!mutations.trySend(mutation).isSuccess) {
            pendingMutations.decrementAndGet()
            error("Preference database writer is unavailable")
        }
    }

    /** Flush pending writes before starting a component in another process. */
    fun flushBlocking() {
        if (pendingMutations.get() == 0) {
            lastWriteError.getAndSet(null)?.let { throw IllegalStateException("Preference write failed", it) }
            return
        }
        val completion = CompletableDeferred<Unit>()
        enqueue(DatabaseMutation.Barrier(completion))
        runBlocking(Dispatchers.IO) { completion.await() }
    }

    /** Refresh this process' cache after another process has flushed configuration changes. */
    fun refreshBlocking() {
        flushBlocking()
        reloadFromDatabase()
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) {
            listeners.toList()
        }
        listeners.forEach { listener ->
            try {
                listener.onPreferenceDataStoreChanged(this, key)
            } catch (error: Throwable) {
                if (
                    error is VirtualMachineError || error is ThreadDeath ||
                    error is LinkageError
                ) throw error
                Logs.w(
                    "Preference listener failed (${error.javaClass.simpleName})",
                )
            }
        }
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
