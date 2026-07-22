package io.nekohasekai.sagernet.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardRhythmConsistencyTest {

    @Test
    fun primaryPagesUseTheSameCardGapAndShape() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = FrameLayout(context)
        val inflater = LayoutInflater.from(context)
        val cards = listOf(
            R.layout.layout_profile,
            R.layout.layout_route_item,
            R.layout.np_preference,
        ).map { inflater.inflate(it, parent, false) as MaterialCardView }
        val expectedHalfGap = context.dp(4)
        val expectedRadius = 18 * context.resources.displayMetrics.density
        val expectedStroke = context.dp(1)

        cards.forEach { card ->
            val margins = card.layoutParams as ViewGroup.MarginLayoutParams
            assertEquals(expectedHalfGap, margins.topMargin)
            assertEquals(expectedHalfGap, margins.bottomMargin)
            assertEquals(expectedRadius, card.radius, 0.01f)
            assertEquals(expectedStroke, card.strokeWidth)
        }

        val settingsContent = cards.last().getChildAt(0)
        assertTrue(
            "Settings cards must retain a touch-friendly standard height",
            settingsContent.minimumHeight >= context.dp(64),
        )
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
