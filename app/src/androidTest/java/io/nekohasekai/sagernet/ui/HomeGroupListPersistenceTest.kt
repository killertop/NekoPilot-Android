package io.nekohasekai.sagernet.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeGroupListPersistenceTest {

    @Test
    fun allSourcesRemainVisibleAcrossRecreationAndNavigation() {
        val fixture = createFixture()
        runBlocking { DataStore.selectProxy(fixture.ungroupedProfileId, fixture.ungroupedId) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertProfilesLoaded(scenario, "UngroupedNode", "NamedGroupNode")

            scenario.recreate()
            assertProfilesLoaded(scenario, "UngroupedNode", "NamedGroupNode")

            reopenHomeThroughSettings(scenario)
            assertProfilesLoaded(scenario, "UngroupedNode", "NamedGroupNode")
        }
    }

    @Test
    fun subscriptionActionsAreVisibleOnFirstHomeDisplay() {
        val fixture = createFixture()
        runBlocking { DataStore.selectProxy(fixture.ungroupedProfileId, fixture.ungroupedId) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val actionsVisible = waitForValue(5_000) {
                var visible = false
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.executePendingTransactions()
                    val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                        io.nekohasekai.sagernet.R.id.toolbar,
                    )
                    visible = toolbar.menu.findItem(
                        io.nekohasekai.sagernet.R.id.action_update_all,
                    )?.isVisible == true && toolbar.menu.findItem(
                        io.nekohasekai.sagernet.R.id.action_manage_subscriptions,
                    )?.isVisible == true
                }
                visible.takeIf { it }
            }
            assertTrue("Subscription actions were not visible on first Home display", actionsVisible == true)
        }
    }

    @Test
    fun deletedPersistedGroupFallsBackToSelectedProfilesSource() {
        val fixture = createFixture()
        runBlocking { DataStore.selectProxy(fixture.namedProfileId, Long.MAX_VALUE) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertProfilesLoaded(scenario, "UngroupedNode", "NamedGroupNode")
            val selectedGroup = waitForValue(5_000) {
                DataStore.configurationStore
                    .getLong(io.nekohasekai.sagernet.Key.PROFILE_GROUP, -1L)
                    .takeIf { it == fixture.namedGroupId }
            }
            assertEquals(fixture.namedGroupId, selectedGroup)
        }
    }

    @Test
    fun sourceAddedWhileHomeIsOpenJoinsUnifiedList() {
        SagerDatabase.proxyDao.reset()
        SagerDatabase.groupDao.reset()
        val ungroupedId = SagerDatabase.groupDao.createGroup(
            ProxyGroup(userOrder = 1L, ungrouped = true, type = GroupType.BASIC),
        )
        val ungroupedProfileId = addProfile(ungroupedId, "UngroupedNode", 1L)
        runBlocking { DataStore.selectProxy(ungroupedProfileId, ungroupedId) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertProfilesLoaded(scenario, "UngroupedNode")

            runBlocking {
                val group = ProxyGroup(
                    userOrder = 2L,
                    name = "Airport",
                    type = GroupType.SUBSCRIPTION,
                )
                group.id = SagerDatabase.groupDao.createGroup(group)
                ProfileManager.createProfile(
                    group.id,
                    SOCKSBean().apply {
                        serverAddress = "127.0.0.1"
                        serverPort = 1081
                        name = "AirportNode"
                    },
                )
            }

            assertProfilesLoaded(scenario, "UngroupedNode", "AirportNode")
        }
    }

    @Test
    fun latencyResultsReorderUnifiedListImmediately() {
        val fixture = createFixture()
        runBlocking { DataStore.selectProxy(fixture.ungroupedProfileId, fixture.ungroupedId) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertProfilesLoaded(scenario, "UngroupedNode", "NamedGroupNode")

            applyLatency(scenario, fixture.namedProfileId, 120)
            assertProfileOrder(scenario, fixture.namedProfileId, fixture.ungroupedProfileId)

            applyLatency(scenario, fixture.ungroupedProfileId, 40)
            assertProfileOrder(scenario, fixture.ungroupedProfileId, fixture.namedProfileId)
        }
    }

    private fun createFixture(): Fixture {
        SagerDatabase.proxyDao.reset()
        SagerDatabase.groupDao.reset()
        val ungroupedId = SagerDatabase.groupDao.createGroup(
            ProxyGroup(userOrder = 1L, ungrouped = true, type = GroupType.BASIC),
        )
        val namedGroupId = SagerDatabase.groupDao.createGroup(
            ProxyGroup(userOrder = 2L, name = "Airport", type = GroupType.SUBSCRIPTION),
        )
        return Fixture(
            ungroupedId,
            namedGroupId,
            addProfile(ungroupedId, "UngroupedNode", 1L),
            addProfile(namedGroupId, "NamedGroupNode", 1L),
        )
    }

    private fun addProfile(groupId: Long, name: String, order: Long): Long {
        val bean = SOCKSBean().apply {
            serverAddress = "127.0.0.1"
            serverPort = 1080
            this.name = name
            initializeDefaultValues()
        }
        return SagerDatabase.proxyDao.addProxy(
            ProxyEntity(groupId = groupId, userOrder = order).putBean(bean),
        )
    }

    private fun reopenHomeThroughSettings(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.displayFragmentWithId(io.nekohasekai.sagernet.R.id.nav_settings)
            activity.supportFragmentManager.executePendingTransactions()
            activity.displayFragmentWithId(io.nekohasekai.sagernet.R.id.nav_home)
            activity.supportFragmentManager.executePendingTransactions()
        }
    }

    private fun applyLatency(
        scenario: ActivityScenario<MainActivity>,
        profileId: Long,
        latencyMs: Int,
    ) {
        val profile = checkNotNull(SagerDatabase.proxyDao.getById(profileId)).apply {
            status = 1
            ping = latencyMs
        }
        scenario.onActivity { activity ->
            findUnifiedAdapter(activity)?.applyTestResult(profile)
        }
    }

    private fun assertProfilesLoaded(
        scenario: ActivityScenario<MainActivity>,
        vararg expectedNames: String,
    ) {
        val snapshot = waitForValue(8_000) {
            var names: List<String>? = null
            scenario.onActivity { activity ->
                val adapter = findUnifiedAdapter(activity) ?: return@onActivity
                if (adapter.itemCount < expectedNames.size) return@onActivity
                names = adapter.configurationIdList.mapNotNull { profileId ->
                    adapter.configurationList[profileId]?.displayName()
                }
            }
            names?.takeIf { it.containsAll(expectedNames.toList()) }
        }
        assertNotNull("Unified home did not render ${expectedNames.toList()}", snapshot)
    }

    private fun assertProfileOrder(
        scenario: ActivityScenario<MainActivity>,
        vararg expectedIds: Long,
    ) {
        val order = waitForValue(5_000) {
            var ids: List<Long>? = null
            scenario.onActivity { activity ->
                ids = findUnifiedAdapter(activity)?.configurationIdList?.toList()
            }
            ids?.takeIf { it.take(expectedIds.size) == expectedIds.toList() }
        }
        assertEquals(expectedIds.toList(), order?.take(expectedIds.size))
    }

    private fun findUnifiedAdapter(
        activity: MainActivity,
    ): ConfigurationFragment.GroupFragment.ConfigurationAdapter? {
        activity.supportFragmentManager.executePendingTransactions()
        val home = activity.supportFragmentManager
            .findFragmentById(io.nekohasekai.sagernet.R.id.fragment_holder)
            as? ConfigurationFragment ?: return null
        home.childFragmentManager.executePendingTransactions()
        return home.childFragmentManager.fragments
            .filterIsInstance<ConfigurationFragment.GroupFragment>()
            .firstNotNullOfOrNull { it.adapter }
    }

    private fun <T> waitForValue(timeoutMillis: Long, block: () -> T?): T? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        do {
            block()?.let { return it }
            Thread.sleep(100L)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private data class Fixture(
        val ungroupedId: Long,
        val namedGroupId: Long,
        val ungroupedProfileId: Long,
        val namedProfileId: Long,
    )
}
