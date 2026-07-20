package io.nekohasekai.sagernet.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class QRCodeDialogSizingTest {

    @Test
    fun keepsConfiguredSizeWhenDialogHasRoom() {
        assertEquals(792, calculateQrCodeSizePx(792, screenWidthDp = 411, density = 3f))
    }

    @Test
    fun shrinksSquareOnNarrowScreens() {
        assertEquals(672, calculateQrCodeSizePx(792, screenWidthDp = 320, density = 3f))
    }
}
