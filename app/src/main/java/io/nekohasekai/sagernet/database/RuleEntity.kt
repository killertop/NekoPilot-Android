package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import kotlinx.parcelize.Parcelize

internal const val CHINA_DOMAIN_RULE = "geosite:cn"
internal const val CHINA_IP_RULE = "geoip:cn"

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
    interface Dao {

        @Query("SELECT * from rules WHERE (packages != '') AND enabled = 1")
        fun checkVpnNeeded(): List<RuleEntity>

        @Query("SELECT * FROM rules ORDER BY userOrder")
        fun allRules(): List<RuleEntity>

        @Query("SELECT * FROM rules WHERE enabled = :enabled ORDER BY userOrder")
        fun enabledRules(enabled: Boolean = true): List<RuleEntity>

        @Query("SELECT MAX(userOrder) + 1 FROM rules")
        fun nextOrder(): Long?

        @Query("SELECT * FROM rules WHERE id = :ruleId")
        fun getById(ruleId: Long): RuleEntity?

        @Query("DELETE FROM rules WHERE id = :ruleId")
        fun deleteById(ruleId: Long): Int

        @Delete
        fun deleteRule(rule: RuleEntity)

        @Delete
        fun deleteRules(rules: List<RuleEntity>)

        @Insert
        fun createRule(rule: RuleEntity): Long

        @Update
        fun updateRule(rule: RuleEntity)

        @Update
        fun updateRules(rules: List<RuleEntity>)

        @Query("DELETE FROM rules")
        fun reset()

        @Insert
        fun insert(rules: List<RuleEntity>)

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
