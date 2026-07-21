package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawUpdaterMatchingTest {
    @Test
    fun renamedNodeMatchesAcrossNamesAndKeepsLocalJsonOverrides() {
        val existing = socks("old name").apply {
            customOutboundJson = "{\"tag\":\"local\"}"
            customConfigJson = "{\"route\":\"local\"}"
        }
        val renamed = socks("new name")
        val changedEndpoint = socks("new name", address = "198.51.100.8")
        val index = SubscriptionIdentityIndex(
            mapOf(7L to existing),
            fingerprintOf = ::testFingerprint,
        )
        val existingIdentity = index.identityForExisting(7L)

        assertEquals(existingIdentity, index.identityForIncoming(renamed))
        assertNotEquals(existingIdentity, index.identityForIncoming(changedEndpoint))
        assertFalse(preserveLocalOverridesAndDetectConfigChange(renamed, existing))
        assertEquals(existing.customOutboundJson, renamed.customOutboundJson)
        assertEquals(existing.customConfigJson, renamed.customConfigJson)
        assertTrue(preserveLocalOverridesAndDetectConfigChange(changedEndpoint, existing))
        assertFalse(
            autoSwitchSelectorSetChanged(
                autoSwitch = true,
                configUpdated = false,
                added = false,
                deleted = false,
            )
        )
        assertTrue(
            autoSwitchSelectorSetChanged(
                autoSwitch = true,
                configUpdated = true,
                added = false,
                deleted = false,
            )
        )
    }

    @Test
    fun fingerprintCollisionStillRequiresFullIdentityEquality() {
        val index = SubscriptionIdentityIndex(
            existingBeansById = mapOf(
                1L to socks("first", address = "192.0.2.1"),
                2L to socks("second", address = "192.0.2.2"),
            ),
            fingerprintOf = { _, _ -> "forced collision" },
        )
        val firstIdentity = index.identityForExisting(1L)
        val secondIdentity = index.identityForExisting(2L)

        assertNotEquals(firstIdentity, secondIdentity)
        assertEquals(secondIdentity, index.identityForIncoming(socks("renamed", address = "192.0.2.2")))
    }

    @Test
    fun partialSubscriptionParseNeverDeletesSkippedProfiles() {
        assertTrue(
            preserveDeletionAfterPartialParse(
                hasNamedSkipped = true,
                hasUnnamedSkipped = false,
            )
        )
        assertTrue(
            preserveDeletionAfterPartialParse(
                hasNamedSkipped = false,
                hasUnnamedSkipped = true,
            )
        )
        assertFalse(
            preserveDeletionAfterPartialParse(
                hasNamedSkipped = false,
                hasUnnamedSkipped = false,
            )
        )
    }

    @Test
    fun uniqueThousandNodeIndexPerformsOneEqualityCheckPerLookup() {
        val size = 1_000
        val existing = (1..size).associate { index ->
            index.toLong() to socks("old $index", port = 10_000 + index)
        }
        var equalityChecks = 0
        val identityIndex = SubscriptionIdentityIndex(
            existingBeansById = existing,
            fingerprintOf = ::testFingerprint,
            identitiesEqual = { left, right ->
                equalityChecks++
                left.contentEquals(right)
            },
        )

        val matchedIdentities = (1..size).map { index ->
            identityIndex.identityForIncoming(socks("new $index", port = 10_000 + index))
        }

        assertEquals(
            (1..size).map { index -> identityIndex.identityForExisting(index.toLong()) },
            matchedIdentities,
        )
        assertEquals(size, equalityChecks)
    }

    @Test
    fun tenThousandIdenticalNodesReuseOneVerifiedIdentityClass() {
        val size = 10_000
        var equalityChecks = 0
        val identityIndex = SubscriptionIdentityIndex(
            existingBeansById = (1..size).associate { index ->
                index.toLong() to socks("old $index")
            },
            fingerprintOf = ::testFingerprint,
            identitiesEqual = { left, right ->
                equalityChecks++
                left.contentEquals(right)
            },
        )
        val expectedIdentity = identityIndex.identityForExisting(1L)

        repeat(size) { index ->
            assertEquals(
                expectedIdentity,
                identityIndex.identityForIncoming(socks("new $index")),
            )
        }

        assertEquals(size * 2 - 1, equalityChecks)
    }

    private fun socks(
        name: String,
        address: String = "192.0.2.7",
        port: Int = 1080,
    ) = SOCKSBean().apply {
        serverAddress = address
        serverPort = port
        this.name = name
        initializeDefaultValues()
    }

    private fun testFingerprint(modelClass: String, encoded: ByteArray): String =
        "$modelClass:${encoded.contentHashCode()}"
}
