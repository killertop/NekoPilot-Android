package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleAssetDialogActionsTest {

    @Test
    fun updateInProgressCanOnlyBeMinimized() {
        assertEquals(
            RuleAssetDialogActions(
                showRetry = false,
                showSecondaryAction = true,
                secondaryActionClosesUpdate = false,
            ),
            ruleAssetDialogActions(hasResult = false, hasFailure = false),
        )
    }

    @Test
    fun failureRemainsActionableWithRetryAndClose() {
        assertEquals(
            RuleAssetDialogActions(
                showRetry = true,
                showSecondaryAction = true,
                secondaryActionClosesUpdate = true,
            ),
            ruleAssetDialogActions(hasResult = false, hasFailure = true),
        )
    }

    @Test
    fun successWaitsForAutomaticDismissWithoutActions() {
        assertEquals(
            RuleAssetDialogActions(
                showRetry = false,
                showSecondaryAction = false,
                secondaryActionClosesUpdate = false,
            ),
            ruleAssetDialogActions(hasResult = true, hasFailure = false),
        )
    }
}
