package io.nekohasekai.sagernet.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeGroupListPersistenceTest {

    @Test
    fun ungroupedAndNamedGroupRemainVisibleAcrossSwitchAndRecreation() {
        val fixture = createFixture()
        DataStore.selectedGroup = fixture.ungroupedId
        DataStore.selectedProxy = fixture.ungroupedProfileId

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertGroupLoaded(scenario, fixture.ungroupedId, "UngroupedNode")
            selectGroup(scenario, fixture.namedGroupId)
            assertGroupLoaded(scenario, fixture.namedGroupId, "NamedGroupNode")

            scenario.recreate()

            assertGroupLoaded(scenario, fixture.namedGroupId, "NamedGroupNode")

            reopenHomeThroughSettings(scenario)
            assertGroupLoaded(scenario, fixture.namedGroupId, "NamedGroupNode")
            selectGroup(scenario, fixture.ungroupedId)
            assertGroupLoaded(scenario, fixture.ungroupedId, "UngroupedNode")
        }
    }

    @Test
    fun deletedPersistedGroupFallsBackToSelectedProfilesGroup() {
        val fixture = createFixture()
        DataStore.selectedGroup = Long.MAX_VALUE
        DataStore.selectedProxy = fixture.namedProfileId

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertGroupLoaded(scenario, fixture.namedGroupId, "NamedGroupNode")
        }
    }

    @Test
    fun groupAddedWhileHomeIsOpenKeepsBothPagesPopulated() {
        SagerDatabase.proxyDao.reset()
        SagerDatabase.groupDao.reset()
        val ungroupedId = SagerDatabase.groupDao.createGroup(
            ProxyGroup(userOrder = 1L, ungrouped = true, type = GroupType.BASIC),
        )
        val ungroupedProfileId = addProfile(ungroupedId, "UngroupedNode", 1L)
        DataStore.selectedGroup = ungroupedId
        DataStore.selectedProxy = ungroupedProfileId

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertGroupLoaded(scenario, ungroupedId, "UngroupedNode")

            val namedGroup = runBlocking {
                GroupManager.createGroup(
                    ProxyGroup(name = "NamedGroup", type = GroupType.BASIC),
                ).also { group ->
                    ProfileManager.createProfile(
                        group.id,
                        SOCKSBean().apply {
                            serverAddress = "127.0.0.1"
                            serverPort = 1081
                            name = "NamedGroupNode"
                        },
                    )
                }
            }

            selectGroup(scenario, namedGroup.id)
            assertGroupLoaded(scenario, namedGroup.id, "NamedGroupNode")
            selectGroup(scenario, ungroupedId)
            assertGroupLoaded(scenario, ungroupedId, "UngroupedNode")
        }
    }

    private fun createFixture(): Fixture {
        SagerDatabase.proxyDao.reset()
        SagerDatabase.groupDao.reset()

        val ungroupedId = SagerDatabase.groupDao.createGroup(
            ProxyGroup(userOrder = 1L, ungrouped = true, type = GroupType.BASIC),
        )
        val namedGroupId = SagerDatabase.groupDao.createGroup(
            ProxyGroup(
                userOrder = 2L,
                name = "NamedGroup",
                type = GroupType.BASIC,
            ),
        )
        val ungroupedProfileId = addProfile(ungroupedId, "UngroupedNode", 1L)
        val namedProfileId = addProfile(namedGroupId, "NamedGroupNode", 1L)
        return Fixture(ungroupedId, namedGroupId, ungroupedProfileId, namedProfileId)
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

    private fun selectGroup(scenario: ActivityScenario<MainActivity>, groupId: Long) {
        val selected = waitForValue(5_000) {
            var changed = false
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(io.nekohasekai.sagernet.R.id.fragment_holder)
                    as? ConfigurationFragment ?: return@onActivity
                val index = fragment.adapter.groupList.indexOfFirst { it.id == groupId }
                if (index >= 0) {
                    fragment.groupPager.setCurrentItem(index, false)
                    changed = true
                }
            }
            changed.takeIf { it }
        }
        assertEquals("Target group could not be selected", true, selected)
    }

    private fun reopenHomeThroughSettings(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.displayFragmentWithId(io.nekohasekai.sagernet.R.id.nav_settings)
            activity.supportFragmentManager.executePendingTransactions()
            activity.displayFragmentWithId(io.nekohasekai.sagernet.R.id.nav_home)
            activity.supportFragmentManager.executePendingTransactions()
        }
    }

    private fun assertGroupLoaded(
        scenario: ActivityScenario<MainActivity>,
        groupId: Long,
        expectedProfileName: String,
    ) {
        val snapshot = waitForValue(8_000) {
            var result: LoadedGroup? = null
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                val fragment = activity.supportFragmentManager
                    .findFragmentById(io.nekohasekai.sagernet.R.id.fragment_holder)
                    as? ConfigurationFragment ?: return@onActivity
                fragment.childFragmentManager.executePendingTransactions()
                val visibleGroup = fragment.adapter.groupList
                    .getOrNull(fragment.groupPager.currentItem)
                    ?: return@onActivity
                val groupFragment = fragment.childFragmentManager
                    .findFragmentByTag("f$groupId") as? ConfigurationFragment.GroupFragment
                    ?: return@onActivity
                val groupAdapter = groupFragment.adapter ?: return@onActivity
                if (groupAdapter.itemCount == 0) return@onActivity
                val profileNames = groupAdapter.configurationIdList.mapNotNull { profileId ->
                    groupAdapter.configurationList[profileId]?.displayName()
                }
                result = LoadedGroup(visibleGroup.id, profileNames)
            }
            result?.takeIf {
                it.visibleGroupId == groupId && expectedProfileName in it.profileNames
            }
        }
        assertNotNull(
            "Group $groupId did not render profile $expectedProfileName",
            snapshot,
        )
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

    private data class LoadedGroup(
        val visibleGroupId: Long,
        val profileNames: List<String>,
    )
}
