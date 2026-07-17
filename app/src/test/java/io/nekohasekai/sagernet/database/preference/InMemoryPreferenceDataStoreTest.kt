package io.nekohasekai.sagernet.database.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryPreferenceDataStoreTest {

    @Test
    fun storesTypedValuesAndDefensivelyCopiesSets() {
        val store = InMemoryPreferenceDataStore()
        val source = mutableSetOf("one")

        store.putBoolean("boolean", true)
        store.putInt("int", 42)
        store.putStringSet("set", source)
        source.add("mutated")

        assertEquals(true, store.getBoolean("boolean"))
        assertEquals(42, store.getInt("int"))
        assertEquals(setOf("one"), store.getStringSet("set"))

        store.putBoolean("boolean", null)
        assertNull(store.getBoolean("boolean"))
        assertFalse(store.getBoolean("missing", false))
    }
}
