package io.nekohasekai.sagernet.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.DataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun missingOrDuplicatedBundledRulesAreRepaired() {
        val dao = SagerDatabase.rulesDao
        dao.reset()
        dao.createRule(
            RuleEntity(
                domains = CHINA_DOMAIN_RULE,
                outbound = -1L,
                enabled = true,
                userOrder = 1L,
            )
        )
        dao.createRule(
            RuleEntity(
                domains = CHINA_DOMAIN_RULE,
                outbound = -1L,
                enabled = true,
                userOrder = 2L,
            )
        )

        val rules = ProfileManager.getRules()

        assertEquals(2, rules.size)
        assertEquals(1, rules.count(RuleEntity::isDefaultChinaDomainDirectRule))
        assertEquals(1, rules.count(RuleEntity::isDefaultChinaIpDirectRule))
    }

    @Test
    fun concurrentRecoveryStillCreatesExactlyOneOfEachBundledRule() {
        val dao = SagerDatabase.rulesDao
        dao.reset()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) {
                executor.submit<List<RuleEntity>> {
                    start.await(5, TimeUnit.SECONDS)
                    ProfileManager.getRules()
                }
            }
            start.countDown()
            calls.forEach { it.get(10, TimeUnit.SECONDS) }

            val rules = dao.allRules()
            assertEquals(2, rules.size)
            assertEquals(1, rules.count(RuleEntity::isDefaultChinaDomainDirectRule))
            assertEquals(1, rules.count(RuleEntity::isDefaultChinaIpDirectRule))
        } finally {
            executor.shutdownNow()
        }
    }
}
