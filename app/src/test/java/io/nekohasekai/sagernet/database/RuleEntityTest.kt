package io.nekohasekai.sagernet.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEntityTest {

    @Test
    fun identifiesTheTwoStockChinaDirectRules() {
        val chinaDomainRule = RuleEntity(domains = CHINA_DOMAIN_RULE, outbound = -1)
        val chinaIpRule = RuleEntity(ip = CHINA_IP_RULE, outbound = -1)

        assertTrue(chinaDomainRule.isDefaultChinaDirectRule())
        assertTrue(chinaDomainRule.isDefaultChinaDomainDirectRule())
        assertTrue(chinaIpRule.isDefaultChinaDirectRule())
        assertTrue(chinaIpRule.isDefaultChinaIpDirectRule())
    }

    @Test
    fun preservesCustomOrNonDirectChinaRules() {
        assertFalse(
            RuleEntity(
                domains = CHINA_DOMAIN_RULE,
                outbound = 0,
            ).isDefaultChinaDirectRule()
        )
        assertFalse(
            RuleEntity(
                ip = CHINA_IP_RULE,
                outbound = -1,
                network = "tcp",
            ).isDefaultChinaDirectRule()
        )
    }
}
