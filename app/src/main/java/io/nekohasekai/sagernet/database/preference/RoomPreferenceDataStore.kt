package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.room.withTransaction
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
    private val database: RoomDatabase,
    private val kvPairDao: KeyValuePair.Dao,
) :
    PreferenceDataStore() {

    data class LongPairSnapshot(
        val first: Long?,
        val second: Long?,
    )

    // Keep construction non-blocking. Application/UI code can read safe defaults immediately,
    // while connection and persistence boundaries explicitly await [awaitReady].
    private val cache = AtomicReference<Map<String, KeyValuePair>>(emptyMap())
    private val readiness = CompletableDeferred<Unit>()
    private val stateLock = Any()
    private val localGeneration = AtomicLong()
    private val pendingMutations = AtomicInteger()
    private val lastWriteError = AtomicReference<Throwable?>()
    private val mutations = Channel<DatabaseMutation>(Channel.UNLIMITED)
    // Background initialization can complete immediately after launch, so listener storage must
    // exist before either coroutine is started from init.
    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
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
                reloadUntilCommitted()
                readiness.complete(Unit)
            } catch (error: Throwable) {
                lastWriteError.set(error)
                readiness.completeExceptionally(error)
                Logs.e(error)
            }
        }
    }

    private fun sameValue(left: KeyValuePair?, right: KeyValuePair?) = when {
        left === right -> true
        left == null || right == null -> false
        else -> left.valueType == right.valueType && left.value.contentEquals(right.value)
    }

    private fun reloadFromDatabase(beforeCommit: (() -> Unit)? = null): Boolean {
        val generation = localGeneration.get()
        val updated = kvPairDao.all().associateBy(KeyValuePair::key)
        beforeCommit?.invoke()
        val previous = synchronized(stateLock) {
            // The validation and cache swap must be one atomic operation with respect to
            // putValue/remove/reset. Otherwise a local write can land after the checks but
            // before getAndSet(), allowing this stale database snapshot to overwrite it.
            if (pendingMutations.get() != 0 || generation != localGeneration.get()) return false
            cache.getAndSet(updated)
        }
        (previous.keys + updated.keys)
            .filter { !sameValue(previous[it], updated[it]) }
            .forEach(::fireChangeListener)
        return true
    }

    private suspend fun reloadUntilCommitted() {
        while (true) {
            val committed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                reloadFromDatabase()
            }
            if (committed) return
            // A local mutation raced the query. Drain it and retry so an API never returns with
            // its own process cache older than the transaction it just committed.
            flush()
        }
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

    /** Flush pending writes without blocking the calling thread. */
    suspend fun flush() {
        if (pendingMutations.get() == 0) {
            lastWriteError.getAndSet(null)?.let { throw IllegalStateException("Preference write failed", it) }
            return
        }
        val completion = CompletableDeferred<Unit>()
        enqueue(DatabaseMutation.Barrier(completion))
        completion.await()
    }

    /** Flush pending writes before starting a component in another process. */
    fun flushBlocking() {
        runBlocking(Dispatchers.IO) { flush() }
    }

    /** Waits for the initial Room snapshot without occupying the caller thread. */
    suspend fun awaitReady() {
        readiness.await()
    }

    /** Refresh this process' cache after another process has flushed configuration changes. */
    suspend fun refresh() {
        awaitReady()
        flush()
        reloadUntilCommitted()
    }

    fun refreshBlocking() {
        runBlocking(Dispatchers.IO) { refresh() }
    }

    /**
     * Creates a nonblank string exactly once across every app process.
     *
     * A regular cache check followed by [putString] is not sufficient for secrets: two
     * processes can both observe a missing key and publish different values. Room's IGNORE
     * conflict strategy makes the database row the single winner. Older releases could leave a
     * blank row behind, so a conditional SQL update repairs only a blank/wrong-type row without
     * replacing a concurrently initialized nonblank winner.
     */
    suspend fun getOrPutString(key: String, createValue: () -> String): String {
        awaitReady()
        getString(key)?.takeIf(String::isNotBlank)?.let { return it }
        flush()
        val candidate = createValue().also {
            require(it.isNotBlank()) { "Preference value must not be blank" }
        }
        val stored: String = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val value = KeyValuePair(key).put(candidate)
            var resolved: String? = null
            while (resolved == null) {
                val current = kvPairDao[key]?.string
                if (!current.isNullOrBlank()) {
                    resolved = current
                    continue
                }
                if (kvPairDao.putIfAbsent(value) != -1L) {
                    resolved = candidate
                    continue
                }
                if (
                    kvPairDao.replaceIfBlankString(
                        key = key,
                        valueType = value.valueType,
                        value = value.value,
                        stringType = KeyValuePair.TYPE_STRING,
                    ) == 1
                ) {
                    resolved = candidate
                }
            }
            checkNotNull(resolved)
        }
        reloadUntilCommitted()
        return stored
    }

    fun getOrPutStringBlocking(key: String, createValue: () -> String): String =
        runBlocking(Dispatchers.IO) { getOrPutString(key, createValue) }

    suspend fun getLongPair(firstKey: String, secondKey: String): LongPairSnapshot {
        awaitReady()
        flush()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                LongPairSnapshot(kvPairDao[firstKey]?.long, kvPairDao[secondKey]?.long)
            }
        }
    }

    fun getLongPairBlocking(firstKey: String, secondKey: String): LongPairSnapshot =
        runBlocking(Dispatchers.IO) { getLongPair(firstKey, secondKey) }

    suspend fun putLongPair(
        firstKey: String,
        firstValue: Long,
        secondKey: String,
        secondValue: Long,
    ) {
        awaitReady()
        flush()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                kvPairDao.put(KeyValuePair(firstKey).put(firstValue))
                kvPairDao.put(KeyValuePair(secondKey).put(secondValue))
            }
        }
        reloadUntilCommitted()
    }

    fun putLongPairBlocking(
        firstKey: String,
        firstValue: Long,
        secondKey: String,
        secondValue: Long,
    ) = runBlocking(Dispatchers.IO) {
        putLongPair(firstKey, firstValue, secondKey, secondValue)
    }

    /**
     * Reads a related set of preference rows from one Room snapshot. The process-local cache is
     * deliberately not used here: a caller that derives behavior from multiple keys must not
     * combine values from different cache generations while another process is publishing them.
     */
    internal suspend fun readValuesAtomically(keys: Collection<String>): Map<String, KeyValuePair> {
        val requestedKeys = keys.toSet()
        require(requestedKeys.isNotEmpty()) { "At least one preference key is required" }
        awaitReady()
        flush()
        val snapshot = kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                kvPairDao.all()
                    .asSequence()
                    .filter { value -> value.key in requestedKeys }
                    .associate { source ->
                        source.key to KeyValuePair(source.key).apply {
                            valueType = source.valueType
                            value = source.value.copyOf()
                        }
                    }
            }
        }
        // Keep the ordinary cache coherent for callers that follow this snapshot read.
        reloadUntilCommitted()
        return snapshot
    }

    /**
     * Replaces a related set of preference rows in one Room transaction. Callers that derive
     * behavior from more than one key (for example a mode plus its allow-list) must use this
     * instead of enqueueing independent writes, so another process can never refresh a partial
     * policy after a failed or interrupted write.
     */
    internal suspend fun putValuesAtomically(values: Collection<KeyValuePair>) {
        val snapshot = snapshotAtomicValues(values)
        awaitReady()
        flush()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                snapshot.forEach(kvPairDao::put)
            }
        }
        reloadUntilCommitted()
    }

    /**
     * Publishes [values] and a new revision in one transaction only while the persisted revision
     * still equals [expectedRevision]. A missing legacy revision is interpreted as [missingRevision].
     */
    internal suspend fun compareAndSetLongWithValues(
        revisionKey: String,
        expectedRevision: Long,
        missingRevision: Long,
        newRevision: Long,
        values: Collection<KeyValuePair>,
    ): Boolean {
        val snapshot = snapshotAtomicValues(values)
        require(snapshot.none { it.key == revisionKey }) {
            "Revision must be written by the compare-and-set transaction"
        }
        awaitReady()
        flush()
        val updated = kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                val currentRevision = kvPairDao[revisionKey]?.long ?: missingRevision
                if (currentRevision != expectedRevision) {
                    false
                } else {
                    snapshot.forEach(kvPairDao::put)
                    kvPairDao.put(KeyValuePair(revisionKey).put(newRevision))
                    true
                }
            }
        }
        reloadUntilCommitted()
        return updated
    }

    /**
     * Commits an applied-state acknowledgement only for [expectedCondition] and atomically
     * increments a durable generation counter. Returns the new generation or null on a stale ack.
     */
    internal suspend fun compareLongAndIncrementCounterWithValues(
        conditionKey: String,
        expectedCondition: Long,
        missingCondition: Long,
        counterKey: String,
        missingCounter: Long,
        counterMirrorKey: String? = null,
        values: Collection<KeyValuePair>,
    ): Long? {
        val snapshot = snapshotAtomicValues(values)
        require(
            snapshot.none {
                it.key == conditionKey || it.key == counterKey || it.key == counterMirrorKey
            },
        ) {
            "Condition and counter keys are owned by the increment transaction"
        }
        awaitReady()
        flush()
        val generation = kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                val currentCondition = kvPairDao[conditionKey]?.long ?: missingCondition
                if (currentCondition != expectedCondition) {
                    null
                } else {
                    val currentCounter = kvPairDao[counterKey]?.long ?: missingCounter
                    check(currentCounter < Long.MAX_VALUE) { "Generation counter exhausted" }
                    val nextCounter = currentCounter + 1L
                    snapshot.forEach(kvPairDao::put)
                    kvPairDao.put(KeyValuePair(counterKey).put(nextCounter))
                    counterMirrorKey?.let { mirrorKey ->
                        kvPairDao.put(KeyValuePair(mirrorKey).put(nextCounter))
                    }
                    nextCounter
                }
            }
        }
        reloadUntilCommitted()
        return generation
    }

    /**
     * Publishes values only while both a revision and an attempt token still identify the caller.
     * This makes runtime receipts idempotent and prevents an older attempt for the same revision
     * from changing the result of a newer attempt.
     */
    internal suspend fun compareLongAndStringWithValues(
        longConditionKey: String,
        expectedLong: Long,
        missingLong: Long,
        stringConditionKey: String,
        expectedString: String,
        missingString: String = "",
        values: Collection<KeyValuePair>,
    ): Boolean {
        val snapshot = snapshotAtomicValues(values)
        require(
            snapshot.none {
                it.key == longConditionKey || it.key == stringConditionKey
            },
        ) {
            "Condition keys are owned by the compare transaction"
        }
        awaitReady()
        flush()
        val updated = kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                val currentLong = kvPairDao[longConditionKey]?.long ?: missingLong
                val currentString = kvPairDao[stringConditionKey]?.string ?: missingString
                if (currentLong != expectedLong || currentString != expectedString) {
                    false
                } else {
                    snapshot.forEach(kvPairDao::put)
                    true
                }
            }
        }
        reloadUntilCommitted()
        return updated
    }

    private fun snapshotAtomicValues(values: Collection<KeyValuePair>): List<KeyValuePair> {
        val snapshot = values.map { source ->
            KeyValuePair(source.key).apply {
                valueType = source.valueType
                value = source.value.copyOf()
            }
        }
        require(snapshot.isNotEmpty()) { "At least one preference value is required" }
        require(snapshot.map { it.key }.toSet().size == snapshot.size) {
            "Atomic preference writes require unique keys"
        }
        return snapshot
    }

    suspend fun compareAndSetLongPair(
        firstKey: String,
        expectedFirst: Long?,
        secondKey: String,
        expectedSecond: Long?,
        newFirst: Long,
        newSecond: Long,
    ): Boolean {
        awaitReady()
        flush()
        val updated = kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.withTransaction {
                val currentFirst = kvPairDao[firstKey]?.long
                val currentSecond = kvPairDao[secondKey]?.long
                if (currentFirst != expectedFirst || currentSecond != expectedSecond) {
                    false
                } else {
                    kvPairDao.put(KeyValuePair(firstKey).put(newFirst))
                    kvPairDao.put(KeyValuePair(secondKey).put(newSecond))
                    true
                }
            }
        }
        reloadUntilCommitted()
        return updated
    }

    fun compareAndSetLongPairBlocking(
        firstKey: String,
        expectedFirst: Long?,
        secondKey: String,
        expectedSecond: Long?,
        newFirst: Long,
        newSecond: Long,
    ): Boolean = runBlocking(Dispatchers.IO) {
        compareAndSetLongPair(
            firstKey,
            expectedFirst,
            secondKey,
            expectedSecond,
            newFirst,
            newSecond,
        )
    }

    internal fun reloadFromDatabaseForTest(beforeCommit: () -> Unit): Boolean =
        reloadFromDatabase(beforeCommit)

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
