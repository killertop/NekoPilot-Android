package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppProxyPolicyTest {

    @Test
    fun draftKeepsEditsLocalUntilItsCallerMarksThemCommitted() {
        val initial = PerAppProxyPolicy.create(
            enabled = true,
            packages = listOf("com.example.one"),
        )
        val draft = PerAppProxyPolicyDraft(initial)

        draft.setEnabled(false)
        draft.replacePackages(listOf("com.example.two", "com.example.three"))

        assertTrue(draft.isDirty)
        assertEquals(4, draft.changeCount)
        assertEquals(initial, PerAppProxyPolicy.create(true, listOf("com.example.one")))
        assertEquals(
            setOf("com.example.two", "com.example.three"),
            draft.packages,
        )

        draft.markCommitted()

        assertFalse(draft.isDirty)
        assertEquals(0, draft.changeCount)
    }

    @Test
    fun discardRestoresThePersistedPolicyAndConfigurationChangeKeepsBaseline() {
        val baseline = PerAppProxyPolicy.create(
            enabled = false,
            packages = listOf("com.example.saved"),
        )
        val draft = PerAppProxyPolicyDraft(baseline)

        draft.restoreDraft(
            PerAppProxyPolicy.create(
                enabled = true,
                packages = listOf("com.example.pending"),
            ),
        )

        assertTrue(draft.isDirty)
        assertEquals(3, draft.changeCount)
        assertEquals(baseline, draft.discard())
        assertFalse(draft.isDirty)
    }

    @Test
    fun policyCanonicalizesPersistedPackageText() {
        val policy = PerAppProxyPolicy.fromStorage(
            enabled = true,
            serializedPackages = "\ncom.example.b\n\uFEFFcom.example.a\ncom.example.b\n",
        )

        assertEquals(setOf("com.example.a", "com.example.b"), policy.packages)
        assertEquals("com.example.a\ncom.example.b", policy.serializedPackages)
    }

    @Test
    fun completedApplyCommitsOnlyThePolicyThatWasActuallyPersisted() {
        val initial = PerAppProxyPolicy.create(false, listOf("com.example.saved"))
        val persisted = PerAppProxyPolicy.create(true, listOf("com.example.applied"))
        val laterDraft = PerAppProxyPolicy.create(true, listOf("com.example.later"))
        val draft = PerAppProxyPolicyDraft(initial)

        draft.restoreDraft(laterDraft)
        draft.markCommitted(persisted)

        assertTrue(draft.isDirty)
        assertEquals(persisted, draft.committedPolicy)
        assertEquals(laterDraft, draft.policy)
    }

    @Test
    fun cleanDraftCanRebaseButRestoredLocalEditKeepsItsOriginalBaseline() {
        val revisionSeven = PerAppProxyPolicy.create(false, listOf("com.example.seven"))
        val revisionEight = PerAppProxyPolicy.create(true, listOf("com.example.eight"))
        val localDraft = PerAppProxyPolicy.create(true, listOf("com.example.local"))
        val draft = PerAppProxyPolicyDraft(revisionSeven)

        draft.rebase(revisionEight)
        assertFalse(draft.isDirty)
        assertEquals(revisionEight, draft.committedPolicy)
        assertEquals(revisionEight, draft.policy)

        draft.restoreDraft(localDraft)
        assertTrue(draft.isDirty)
        assertEquals(revisionEight, draft.committedPolicy)
        assertEquals(localDraft, draft.policy)
    }

    @Test
    fun recommendationPreparationSurvivesOnlyWhenItWasStillPending() {
        assertTrue(
            shouldPreparePerAppRecommendations(
                firstEntrySetupPending = true,
                draftIsEmpty = true,
                restoredPending = true,
            ),
        )
        assertFalse(
            shouldPreparePerAppRecommendations(
                firstEntrySetupPending = true,
                draftIsEmpty = true,
                restoredPending = false,
            ),
        )
        assertFalse(
            shouldPreparePerAppRecommendations(
                firstEntrySetupPending = true,
                draftIsEmpty = false,
                restoredPending = true,
            ),
        )
        assertFalse(
            shouldPreparePerAppRecommendations(
                firstEntrySetupPending = false,
                draftIsEmpty = true,
                restoredPending = true,
            ),
        )
    }

    @Test
    fun coreSystemUidsAreNotIndividuallySelectable() {
        assertFalse(isPerAppSelectableUid(0))
        assertFalse(isPerAppSelectableUid(1_000))
        assertFalse(isPerAppSelectableUid(9_999))
        assertTrue(isPerAppSelectableUid(10_000))
        assertTrue(isPerAppSelectableUid(10_383))
        assertFalse(isPerAppSelectableUid(101_000))
        assertFalse(isPerAppSelectableUid(109_999))
        assertTrue(isPerAppSelectableUid(110_000))
        assertTrue(isPerAppSelectableUid(110_383))
        assertFalse(isPerAppSelectableUid(-1))
    }

    @Test
    fun removesInstalledCoreComponentsButPreservesAppsAndMissingPackages() {
        val result = sanitizePerAppPackages(
            selectedPackages = listOf(
                "android",
                "com.xiaomi.aiasst.service",
                "com.openai.chatgpt",
                "com.example.not.installed",
            ),
            installedUids = mapOf(
                "android" to 1_000,
                "com.xiaomi.aiasst.service" to 1_000,
                "com.openai.chatgpt" to 10_383,
            ),
        )

        assertEquals(
            linkedSetOf("com.openai.chatgpt", "com.example.not.installed"),
            result,
        )
    }

    @Test
    fun normalizesBlankBomAndDuplicateEntries() {
        assertEquals(
            linkedSetOf("com.openai.chatgpt"),
            sanitizePerAppPackages(
                selectedPackages = listOf("", "\uFEFFcom.openai.chatgpt", "com.openai.chatgpt"),
                installedUids = mapOf("com.openai.chatgpt" to 10_383),
            ),
        )
    }

    @Test
    fun editingVisibleAppsPreservesPackagesHiddenBySystemPermission() {
        assertEquals(
            linkedSetOf("com.hidden.saved", "com.visible.selected"),
            mergeVisiblePerAppSelection(
                savedPackages = listOf("com.hidden.saved", "com.visible.old"),
                visiblePackages = setOf("com.visible.old", "com.visible.selected"),
                selectedVisiblePackages = listOf("com.visible.selected"),
            ),
        )
    }
}
