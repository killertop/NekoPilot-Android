package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
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

    @Test
    fun validatesMatchConditionsIndependentlyFromDirtyOrName() {
        assertEquals(
            RuleValidationResult.MISSING_MATCH_CONDITION,
            RuleEntity(name = "Named only", outbound = 0).validateRule { false },
        )
        assertEquals(
            RuleValidationResult.VALID,
            RuleEntity(domains = "example.com", outbound = 0).validateRule { false },
        )
        assertEquals(
            RuleValidationResult.VALID,
            RuleEntity(packages = setOf("com.example.app"), outbound = -1).validateRule { false },
        )
        assertEquals(
            RuleValidationResult.VALID,
            RuleEntity(config = "{\"domain_suffix\":[\"example.com\"]}", outbound = -2)
                .validateRule { false },
        )
    }

    @Test
    fun validatesCustomOutboundStillExists() {
        val rule = RuleEntity(ip = "192.0.2.1", outbound = 42L)

        assertEquals(
            RuleValidationResult.INVALID_OUTBOUND,
            rule.validateRule { false },
        )
        assertEquals(
            RuleValidationResult.VALID,
            rule.validateRule { profileId -> profileId == 42L },
        )
        assertEquals(
            RuleValidationResult.INVALID_OUTBOUND,
            rule.copy(outbound = -3L).validateRule { true },
        )
    }
}
