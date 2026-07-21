package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import kotlinx.parcelize.Parcelize

internal const val CHINA_DOMAIN_RULE = "rule_set:geosite-cn"
internal const val CHINA_IP_RULE = "rule_set:geoip-cn"

@Entity(tableName = "rules")
@Parcelize
@TypeConverters(StringCollectionConverter::class)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var name: String = "",
    @ColumnInfo(defaultValue = "")
    var config: String = "",
    var userOrder: Long = 0L,
    var enabled: Boolean = false,
    var domains: String = "",
    var ip: String = "",
    var port: String = "",
    var sourcePort: String = "",
    var network: String = "",
    var source: String = "",
    var protocol: String = "",
    var outbound: Long = 0,
    var packages: Set<String> = emptySet(),
) : Parcelable {

    fun displayName(): String {
        return when {
            isDefaultChinaDomainDirectRule() -> app.getString(R.string.route_china_domain)
            isDefaultChinaIpDirectRule() -> app.getString(R.string.route_china_ip)
            else -> name.takeIf { it.isNotBlank() }
                ?: app.getString(R.string.route_rule_unnamed, id)
        }
    }

    fun mkSummary(): String {
        if (isDefaultChinaDirectRule()) {
            return app.getString(R.string.route_summary_bundled_rule_set)
        }
        val lines = buildList {
            if (config.isNotBlank()) add(app.getString(R.string.route_summary_custom_config))
            if (domains.isNotBlank()) add(domains)
            if (ip.isNotBlank()) add(ip)
            if (source.isNotBlank()) add(app.getString(R.string.route_summary_source_ip, source))
            if (sourcePort.isNotBlank()) {
                add(app.getString(R.string.route_summary_source_port, sourcePort))
            }
            if (port.isNotBlank()) add(app.getString(R.string.route_summary_destination_port, port))
            if (network.isNotBlank()) add(app.getString(R.string.route_summary_network, network))
            if (protocol.isNotBlank()) add(app.getString(R.string.route_summary_protocol, protocol))
            if (packages.isNotEmpty()) add(app.getString(R.string.apps_message, packages.size))
        }
        return if (lines.size > 3) {
            lines.take(3).joinToString("\n", postfix = "\n…")
        } else {
            lines.joinToString("\n")
        }
    }

    fun displayOutbound(): String {
        return when (outbound) {
            0L -> app.getString(R.string.route_proxy)
            -1L -> app.getString(R.string.route_bypass)
            -2L -> app.getString(R.string.route_block)
            else -> ProfileManager.getProfile(outbound)?.displayName()
                ?: app.getString(R.string.error_title)
        }
    }

    @androidx.room.Dao
    abstract class Dao {

        @Query("SELECT * from rules WHERE (packages != '') AND enabled = 1")
        abstract fun checkVpnNeeded(): List<RuleEntity>

        @Query("SELECT * FROM rules ORDER BY userOrder")
        abstract fun allRules(): List<RuleEntity>

        @Query("SELECT * FROM rules WHERE enabled = :enabled ORDER BY userOrder")
        abstract fun enabledRules(enabled: Boolean = true): List<RuleEntity>

        @Query("SELECT MAX(userOrder) + 1 FROM rules")
        abstract fun nextOrder(): Long?

        @Query("SELECT * FROM rules WHERE id = :ruleId")
        abstract fun getById(ruleId: Long): RuleEntity?

        @Query("DELETE FROM rules WHERE id = :ruleId")
        protected abstract fun deleteAnyById(ruleId: Long): Int

        /** Atomically repairs missing/duplicated bundled rules across app processes. */
        @Transaction
        open fun ensureDefaultChinaRules(domainName: String, ipName: String): List<RuleEntity> {
            var rules = allRules()
            rules.filter(RuleEntity::isDefaultChinaDomainDirectRule).drop(1)
                .forEach { deleteAnyById(it.id) }
            rules.filter(RuleEntity::isDefaultChinaIpDirectRule).drop(1)
                .forEach { deleteAnyById(it.id) }
            rules = allRules()
            var nextOrder = nextOrder() ?: 1L
            if (rules.none(RuleEntity::isDefaultChinaDomainDirectRule)) {
                createRule(
                    RuleEntity(
                        name = domainName,
                        domains = CHINA_DOMAIN_RULE,
                        outbound = -1L,
                        enabled = true,
                        userOrder = nextOrder++,
                    )
                )
            }
            if (rules.none(RuleEntity::isDefaultChinaIpDirectRule)) {
                createRule(
                    RuleEntity(
                        name = ipName,
                        ip = CHINA_IP_RULE,
                        outbound = -1L,
                        enabled = true,
                        userOrder = nextOrder,
                    )
                )
            }
            return allRules()
        }

        /**
         * The two bundled China direct rules are product defaults, not user-created rules.
         * Keep this guard in the DAO as well as in the UI so an old screen, stale gesture,
         * or future bulk-delete caller cannot remove them accidentally.
         */
        @Query(
            """
            DELETE FROM rules
            WHERE id = :ruleId
              AND NOT (
                outbound = -1
                AND config = ''
                AND port = ''
                AND sourcePort = ''
                AND network = ''
                AND source = ''
                AND protocol = ''
                AND packages = ''
                AND (
                  (domains = 'rule_set:geosite-cn' AND ip = '')
                  OR (ip = 'rule_set:geoip-cn' AND domains = '')
                )
              )
            """
        )
        abstract fun deleteById(ruleId: Long): Int

        @Transaction
        open fun deleteRule(rule: RuleEntity) {
            deleteById(rule.id)
        }

        @Transaction
        open fun deleteRules(rules: List<RuleEntity>) {
            rules.forEach { deleteById(it.id) }
        }

        @Insert
        abstract fun createRule(rule: RuleEntity): Long

        @Update
        abstract fun updateRule(rule: RuleEntity)

        @Update
        abstract fun updateRules(rules: List<RuleEntity>)

        @Query("DELETE FROM rules")
        abstract fun reset()

        @Insert
        abstract fun insert(rules: List<RuleEntity>)

    }


}

private fun RuleEntity.hasDefaultChinaDirectShape(): Boolean {
    return !(
        outbound != -1L || config.isNotBlank() || port.isNotBlank() || sourcePort.isNotBlank() ||
        network.isNotBlank() || source.isNotBlank() || protocol.isNotBlank() || packages.isNotEmpty()
    )
}

internal fun RuleEntity.isDefaultChinaDomainDirectRule(): Boolean {
    return hasDefaultChinaDirectShape() && domains == CHINA_DOMAIN_RULE && ip.isBlank()
}

internal fun RuleEntity.isDefaultChinaIpDirectRule(): Boolean {
    return hasDefaultChinaDirectShape() && ip == CHINA_IP_RULE && domains.isBlank()
}

internal fun RuleEntity.isDefaultChinaDirectRule(): Boolean {
    return isDefaultChinaDomainDirectRule() || isDefaultChinaIpDirectRule()
}

internal enum class RuleValidationResult {
    VALID,
    MISSING_MATCH_CONDITION,
    INVALID_OUTBOUND,
}

/**
 * A route rule is useful only when it has at least one matcher and a real target.
 * The display name and dirty flag intentionally do not participate in validity.
 */
internal fun RuleEntity.validateRule(profileExists: (Long) -> Boolean): RuleValidationResult {
    val hasMatchCondition = config.isNotBlank() || domains.isNotBlank() || ip.isNotBlank() ||
        port.isNotBlank() || sourcePort.isNotBlank() || network.isNotBlank() ||
        source.isNotBlank() || protocol.isNotBlank() || packages.any(String::isNotBlank)
    if (!hasMatchCondition) return RuleValidationResult.MISSING_MATCH_CONDITION

    val hasValidOutbound = outbound == 0L || outbound == -1L || outbound == -2L ||
        (outbound > 0L && profileExists(outbound))
    return if (hasValidOutbound) {
        RuleValidationResult.VALID
    } else {
        RuleValidationResult.INVALID_OUTBOUND
    }
}
