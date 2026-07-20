package io.nekohasekai.sagernet.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedRuleDeletionTest {

    @Test
    fun daoProtectsBundledRulesFromSingleAndBulkDeletion() {
        val dao = SagerDatabase.rulesDao
        dao.reset()
        try {
            val defaults = ProfileManager.getRules()
            val domainRule = defaults.first { it.isDefaultChinaDomainDirectRule() }
            val ipRule = defaults.first { it.isDefaultChinaIpDirectRule() }
            val customRule = RuleEntity(
                domains = "example.com",
                outbound = -1,
                enabled = true,
                userOrder = dao.nextOrder() ?: 1L,
            ).also { it.id = dao.createRule(it) }

            assertEquals(0, dao.deleteById(domainRule.id))
            dao.deleteRule(ipRule)
            val removedIds = mutableListOf<Long>()
            val listener = object : ProfileManager.RuleListener {
                override suspend fun onAdd(rule: RuleEntity) = Unit
                override suspend fun onUpdated(rule: RuleEntity) = Unit
                override suspend fun onRemoved(ruleId: Long) {
                    removedIds += ruleId
                }
                override suspend fun onCleared() = Unit
            }
            ProfileManager.addListener(listener)
            try {
                runBlocking {
                    ProfileManager.deleteRules(listOf(domainRule, ipRule, customRule))
                }
            } finally {
                ProfileManager.removeListener(listener)
            }

            assertNotNull(dao.getById(domainRule.id))
            assertNotNull(dao.getById(ipRule.id))
            assertNull(dao.getById(customRule.id))
            assertEquals(listOf(customRule.id), removedIds)
            assertTrue(dao.allRules().all { it.isDefaultChinaDirectRule() })
        } finally {
            dao.reset()
            ProfileManager.getRules()
        }
    }
}
