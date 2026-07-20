package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import java.util.concurrent.ConcurrentHashMap

/** Fast, process-local editing state. This store is intentionally not persisted. */
class InMemoryPreferenceDataStore : PreferenceDataStore() {
    private val values = ConcurrentHashMap<String, Any>()
    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()

    fun getBoolean(key: String) = values[key] as? Boolean
    fun getFloat(key: String) = values[key] as? Float
    fun getInt(key: String) = values[key] as? Int
    fun getLong(key: String) = values[key] as? Long
    fun getString(key: String) = values[key] as? String
    fun getStringSet(key: String) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet()

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        getStringSet(key)?.toMutableSet() ?: defValues

    fun putBoolean(key: String, value: Boolean?) = putNullable(key, value)
    fun putFloat(key: String, value: Float?) = putNullable(key, value)
    fun putInt(key: String, value: Int?) = putNullable(key, value)
    fun putLong(key: String, value: Long?) = putNullable(key, value)

    override fun putBoolean(key: String, value: Boolean) = putValue(key, value)
    override fun putFloat(key: String, value: Float) = putValue(key, value)
    override fun putInt(key: String, value: Int) = putValue(key, value)
    override fun putLong(key: String, value: Long) = putValue(key, value)
    override fun putString(key: String, value: String?) = putNullable(key, value)
    override fun putStringSet(key: String, values: MutableSet<String>?) =
        putNullable(key, values?.toSet())

    fun remove(key: String) {
        if (values.remove(key) != null) fireChangeListener(key)
    }

    fun reset() {
        values.clear()
    }

    private fun putNullable(key: String, value: Any?) {
        if (value == null) remove(key) else putValue(key, value)
    }

    private fun putValue(key: String, value: Any) {
        values[key] = value
        fireChangeListener(key)
    }

    private fun fireChangeListener(key: String) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { listener ->
            try {
                listener.onPreferenceDataStoreChanged(this, key)
            } catch (error: Throwable) {
                if (
                    error is VirtualMachineError || error is ThreadDeath ||
                    error is LinkageError
                ) throw error
                android.util.Log.w(
                    "MemoryPreferenceStore",
                    "Preference listener failed (${error.javaClass.simpleName})",
                )
            }
        }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }
}
