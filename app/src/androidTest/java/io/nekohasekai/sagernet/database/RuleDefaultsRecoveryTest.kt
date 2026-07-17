package io.nekohasekai.sagernet.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.DataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleDefaultsRecoveryTest {

    @Test
    fun emptyRuleTableRecoversDefaultsEvenWhenLegacyFlagIsSet() {
        val dao = SagerDatabase.rulesDao
        dao.reset()
        DataStore.rulesFirstCreate = true
        DataStore.ruleDefaultsVersion = 1

        val rules = ProfileManager.getRules()

        assertEquals(2, rules.size)
        assertTrue(rules.all { it.enabled })
        assertTrue(rules.any { it.isDefaultChinaDomainDirectRule() })
        assertTrue(rules.any { it.isDefaultChinaIpDirectRule() })
    }
}
