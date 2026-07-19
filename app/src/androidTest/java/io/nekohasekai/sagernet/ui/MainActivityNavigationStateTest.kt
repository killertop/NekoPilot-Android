package io.nekohasekai.sagernet.ui

import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationStateTest {

    @Test
    fun secondaryPageKeepsBottomNavigationHiddenAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.displaySecondaryFragment(AboutFragment())
                activity.supportFragmentManager.executePendingTransactions()
                assertFalse(activity.binding.bottomNavigation.isVisible)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertFalse(activity.binding.bottomNavigation.isVisible)
            }
        }
    }
}
