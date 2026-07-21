package io.nekohasekai.sagernet.database

import android.content.Context
import android.content.Intent
import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.profile.*
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.neko.*
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.utils.JavaUtil.gson
import java.util.concurrent.atomic.AtomicLong

@Entity(
    tableName = "proxy_entities",
    indices = [Index(value = ["groupId", "userOrder"], name = "index_proxy_entities_groupId_userOrder")],
)
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var type: Int = 0,
    var userOrder: Long = 0L,
    var status: Int = 0,
    var ping: Int = 0,
    var uuid: String = "",
    var error: String? = null,
    var displayNameCache: String = "",
    var displayAddressCache: String = "",
    var displayTypeCache: String = "",
    var hasExplicitName: Boolean = false,
    var configRevision: Long = 0L,
    var socksBean: SOCKSBean? = null,
    var httpBean: HttpBean? = null,
    var ssBean: ShadowsocksBean? = null,
    var vmessBean: VMessBean? = null,
    var trojanBean: TrojanBean? = null,
    var trojanGoBean: TrojanGoBean? = null,
    var mieruBean: MieruBean? = null,
    var naiveBean: NaiveBean? = null,
    var hysteriaBean: HysteriaBean? = null,
    var tuicBean: TuicBean? = null,
    var sshBean: SSHBean? = null,
    var wgBean: WireGuardBean? = null,
    var shadowTLSBean: ShadowTLSBean? = null,
    var anyTLSBean: AnyTLSBean? = null,
    var chainBean: ChainBean? = null,
    var nekoBean: NekoBean? = null,
    var configBean: ConfigBean? = null,
) : Serializable() {

    data class ProxyMultiplex(
        var enabled: Boolean = false,
        var padding: Boolean = false,
        var maxStreams: Int = 0,
        var protocol: String = "h2mux",
    )

    companion object {
        const val TYPE_SOCKS = 0
        const val TYPE_HTTP = 1
        const val TYPE_SS = 2
        const val TYPE_VMESS = 4
        const val TYPE_TROJAN = 6

        const val TYPE_SSH = 17
        const val TYPE_WG = 18

        const val TYPE_TROJAN_GO = 7
        const val TYPE_NAIVE = 9
        const val TYPE_HYSTERIA = 15
        const val TYPE_SHADOWTLS = 19
        const val TYPE_TUIC = 20
        const val TYPE_MIERU = 21
        const val TYPE_ANYTLS = 22

        const val TYPE_CONFIG = 998
        const val TYPE_NEKO = 999

        const val TYPE_CHAIN = 8

        val chainName by lazy { app.getString(R.string.proxy_chain) }

        private val revisionClock = AtomicLong(System.currentTimeMillis() shl 16)

        private fun nextConfigRevision(): Long = revisionClock.incrementAndGet()

        @JvmField
        val CREATOR = object : CREATOR<ProxyEntity>() {

            override fun newInstance(): ProxyEntity {
                return ProxyEntity()
            }

            override fun newArray(size: Int): Array<ProxyEntity?> {
                return arrayOfNulls(size)
            }
        }
    }

    @Ignore
    @Transient
    var dirty: Boolean = false

    @Ignore
    @Transient
    var downloadMbps: Double? = null

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(3)

        output.writeLong(id)
        output.writeLong(groupId)
        output.writeInt(type)
        output.writeLong(userOrder)
        output.writeInt(status)
        output.writeInt(ping)
        output.writeString(uuid)
        output.writeString(error)

        val data = KryoConverters.serialize(requireBean())
        output.writeVarInt(data.size, true)
        output.writeBytes(data)

        output.writeBoolean(dirty)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()

        id = input.readLong()
        groupId = input.readLong()
        type = input.readInt()
        userOrder = input.readLong()
        if (version == 0 || version == 2) {
            input.readLong()
            input.readLong()
        }
        status = input.readInt()
        ping = input.readInt()
        uuid = input.readString()
        error = input.readString()
        putByteArrayStrict(input.readBytes(input.readVarInt(true)))

        dirty = input.readBoolean()
    }


    fun putByteArray(byteArray: ByteArray) {
        when (type) {
            TYPE_SOCKS -> socksBean = KryoConverters.socksDeserialize(byteArray)
            TYPE_HTTP -> httpBean = KryoConverters.httpDeserialize(byteArray)
            TYPE_SS -> ssBean = KryoConverters.shadowsocksDeserialize(byteArray)
            TYPE_VMESS -> vmessBean = KryoConverters.vmessDeserialize(byteArray)
            TYPE_TROJAN -> trojanBean = KryoConverters.trojanDeserialize(byteArray)
            TYPE_TROJAN_GO -> trojanGoBean = KryoConverters.trojanGoDeserialize(byteArray)
            TYPE_MIERU -> mieruBean = KryoConverters.mieruDeserialize(byteArray)
            TYPE_NAIVE -> naiveBean = KryoConverters.naiveDeserialize(byteArray)
            TYPE_HYSTERIA -> hysteriaBean = KryoConverters.hysteriaDeserialize(byteArray)
            TYPE_SSH -> sshBean = KryoConverters.sshDeserialize(byteArray)
            TYPE_WG -> wgBean = KryoConverters.wireguardDeserialize(byteArray)
            TYPE_TUIC -> tuicBean = KryoConverters.tuicDeserialize(byteArray)
            TYPE_SHADOWTLS -> shadowTLSBean = KryoConverters.shadowTLSDeserialize(byteArray)
            TYPE_ANYTLS -> anyTLSBean = KryoConverters.anyTLSDeserialize(byteArray)
            TYPE_CHAIN -> chainBean = KryoConverters.chainDeserialize(byteArray)
            TYPE_NEKO -> nekoBean = KryoConverters.nekoDeserialize(byteArray)
            TYPE_CONFIG -> configBean = KryoConverters.configDeserialize(byteArray)
        }
    }

    fun putByteArrayStrict(byteArray: ByteArray) {
        when (type) {
            TYPE_SOCKS -> socksBean = KryoConverters.deserializeStrict(SOCKSBean(), byteArray)
            TYPE_HTTP -> httpBean = KryoConverters.deserializeStrict(HttpBean(), byteArray)
            TYPE_SS -> ssBean = KryoConverters.deserializeStrict(ShadowsocksBean(), byteArray)
            TYPE_VMESS -> vmessBean = KryoConverters.deserializeStrict(VMessBean(), byteArray)
            TYPE_TROJAN -> trojanBean = KryoConverters.deserializeStrict(TrojanBean(), byteArray)
            TYPE_TROJAN_GO -> trojanGoBean = KryoConverters.deserializeStrict(TrojanGoBean(), byteArray)
            TYPE_MIERU -> mieruBean = KryoConverters.deserializeStrict(MieruBean(), byteArray)
            TYPE_NAIVE -> naiveBean = KryoConverters.deserializeStrict(NaiveBean(), byteArray)
            TYPE_HYSTERIA -> hysteriaBean = KryoConverters.deserializeStrict(HysteriaBean(), byteArray)
            TYPE_SSH -> sshBean = KryoConverters.deserializeStrict(SSHBean(), byteArray)
            TYPE_WG -> wgBean = KryoConverters.deserializeStrict(WireGuardBean(), byteArray)
            TYPE_TUIC -> tuicBean = KryoConverters.deserializeStrict(TuicBean(), byteArray)
            TYPE_SHADOWTLS -> shadowTLSBean = KryoConverters.deserializeStrict(ShadowTLSBean(), byteArray)
            TYPE_ANYTLS -> anyTLSBean = KryoConverters.deserializeStrict(AnyTLSBean(), byteArray)
            TYPE_CHAIN -> chainBean = KryoConverters.deserializeStrict(ChainBean(), byteArray)
            TYPE_NEKO -> nekoBean = KryoConverters.deserializeStrict(NekoBean(), byteArray)
            TYPE_CONFIG -> configBean = KryoConverters.deserializeStrict(ConfigBean(), byteArray)
            else -> error("Unsupported profile type: $type")
        }
    }

    private fun computeDisplayType(): String = when (type) {
        TYPE_SOCKS -> socksBean!!.protocolNameForUi()
        TYPE_HTTP -> if (httpBean!!.isTLS()) "HTTPS" else "HTTP"
        TYPE_SS -> "Shadowsocks"
        TYPE_VMESS -> if (vmessBean!!.isVLESSProfile()) "VLESS" else "VMess"
        TYPE_TROJAN -> "Trojan"
        TYPE_TROJAN_GO -> "Trojan-Go"
        TYPE_MIERU -> "Mieru"
        TYPE_NAIVE -> "Naïve"
        TYPE_HYSTERIA -> "Hysteria" + hysteriaBean!!.protocolVersion
        TYPE_SSH -> "SSH"
        TYPE_WG -> "WireGuard"
        TYPE_TUIC -> "TUIC"
        TYPE_SHADOWTLS -> "ShadowTLS"
        TYPE_ANYTLS -> "AnyTLS"
        TYPE_CHAIN -> chainName
        TYPE_NEKO -> "invalid"
        TYPE_CONFIG -> configBean!!.displayTypeForUi()
        else -> "Undefined type $type"
    }

    fun displayType(): String = displayTypeCache.ifBlank(::computeDisplayType)

    fun displayName(): String = displayNameCache.ifBlank { requireBean().displayNameForUi() }

    fun displayAddress(): String = displayAddressCache.ifBlank {
        requireBean().displayAddressForUi()
    }

    private fun refreshListMetadata(bean: AbstractBean = requireBean()) {
        displayNameCache = bean.displayNameForUi()
        displayAddressCache = bean.displayAddressForUi()
        displayTypeCache = computeDisplayType()
        hasExplicitName = bean.name.isNotBlank()
    }

    fun toListStub(): ProxyEntity {
        if (displayNameCache.isBlank() || displayTypeCache.isBlank()) refreshListMetadata()
        return ProxyEntity(
            id = id,
            groupId = groupId,
            type = type,
            userOrder = userOrder,
            status = status,
            ping = ping,
            uuid = uuid,
            error = error,
            displayNameCache = displayNameCache,
            displayAddressCache = displayAddressCache,
            displayTypeCache = displayTypeCache,
            hasExplicitName = hasExplicitName,
            configRevision = configRevision,
        ).also { it.downloadMbps = downloadMbps }
    }

    fun requireBean(): AbstractBean {
        return when (type) {
            TYPE_SOCKS -> socksBean
            TYPE_HTTP -> httpBean
            TYPE_SS -> ssBean
            TYPE_VMESS -> vmessBean
            TYPE_TROJAN -> trojanBean
            TYPE_TROJAN_GO -> trojanGoBean
            TYPE_MIERU -> mieruBean
            TYPE_NAIVE -> naiveBean
            TYPE_HYSTERIA -> hysteriaBean
            TYPE_SSH -> sshBean
            TYPE_WG -> wgBean
            TYPE_TUIC -> tuicBean
            TYPE_SHADOWTLS -> shadowTLSBean
            TYPE_ANYTLS -> anyTLSBean
            TYPE_CHAIN -> chainBean
            TYPE_NEKO -> nekoBean
            TYPE_CONFIG -> configBean
            else -> error("Undefined type $type")
        } ?: error("Null ${displayType()} profile")
    }

    fun haveLink(): Boolean {
        return when (type) {
            TYPE_CHAIN -> false
            else -> true
        }
    }

    fun haveStandardLink(): Boolean {
        return type !in setOf(TYPE_SSH, TYPE_WG, TYPE_SHADOWTLS, TYPE_NEKO, TYPE_CONFIG)
    }

    fun toStdLink(compact: Boolean = false): String = with(requireBean()) {
        when (this) {
            is NekoBean -> ""
            is SSHBean, is WireGuardBean, is ShadowTLSBean, is ConfigBean -> toUniversalLink()
            else -> encodeProfileLinkWithGo(this)
        }
    }

    fun exportConfig(): Pair<String, String> {
        return buildConfig(this, forExport = true).config to
            "${requireBean().displayNameForUi()}.json"
    }

    fun singMux(): ProxyMultiplex? {
        return when (type) {
            TYPE_VMESS -> ProxyMultiplex().apply {
                enabled = vmessBean!!.enableMux
                padding = vmessBean!!.muxPadding
                maxStreams = vmessBean!!.muxConcurrency
                protocol = when (vmessBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    else -> "h2mux"
                }
            }

            TYPE_TROJAN -> ProxyMultiplex().apply {
                enabled = trojanBean!!.enableMux
                padding = trojanBean!!.muxPadding
                maxStreams = trojanBean!!.muxConcurrency
                protocol = when (trojanBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    else -> "h2mux"
                }
            }

            else -> null
        }
    }

    fun putBean(bean: AbstractBean): ProxyEntity {
        socksBean = null
        httpBean = null
        ssBean = null
        vmessBean = null
        trojanBean = null
        trojanGoBean = null
        mieruBean = null
        naiveBean = null
        hysteriaBean = null
        sshBean = null
        wgBean = null
        tuicBean = null
        shadowTLSBean = null
        anyTLSBean = null
        chainBean = null
        configBean = null
        nekoBean = null

        when (bean) {
            is SOCKSBean -> {
                type = TYPE_SOCKS
                socksBean = bean
            }

            is HttpBean -> {
                type = TYPE_HTTP
                httpBean = bean
            }

            is ShadowsocksBean -> {
                type = TYPE_SS
                ssBean = bean
            }

            is VMessBean -> {
                type = TYPE_VMESS
                vmessBean = bean
            }

            is TrojanBean -> {
                type = TYPE_TROJAN
                trojanBean = bean
            }

            is TrojanGoBean -> {
                type = TYPE_TROJAN_GO
                trojanGoBean = bean
            }

            is MieruBean -> {
                type = TYPE_MIERU
                mieruBean = bean
            }

            is NaiveBean -> {
                type = TYPE_NAIVE
                naiveBean = bean
            }

            is HysteriaBean -> {
                type = TYPE_HYSTERIA
                hysteriaBean = bean
            }

            is SSHBean -> {
                type = TYPE_SSH
                sshBean = bean
            }

            is WireGuardBean -> {
                type = TYPE_WG
                wgBean = bean
            }

            is TuicBean -> {
                type = TYPE_TUIC
                tuicBean = bean
            }

            is ShadowTLSBean -> {
                type = TYPE_SHADOWTLS
                shadowTLSBean = bean
            }

            is AnyTLSBean -> {
                type = TYPE_ANYTLS
                anyTLSBean = bean
            }

            is ChainBean -> {
                type = TYPE_CHAIN
                chainBean = bean
            }

            is NekoBean -> {
                type = TYPE_NEKO
                nekoBean = bean
            }

            is ConfigBean -> {
                type = TYPE_CONFIG
                configBean = bean
            }

            else -> error("Undefined type $type")
        }
        configRevision = nextConfigRevision()
        refreshListMetadata(bean)
        return this
    }

    fun settingIntent(ctx: Context, isSubscription: Boolean): Intent {
        return Intent(
            ctx, when (type) {
                TYPE_SOCKS -> SocksSettingsActivity::class.java
                TYPE_HTTP -> HttpSettingsActivity::class.java
                TYPE_SS -> ShadowsocksSettingsActivity::class.java
                TYPE_VMESS -> VMessSettingsActivity::class.java
                TYPE_TROJAN -> TrojanSettingsActivity::class.java
                TYPE_TROJAN_GO -> TrojanGoSettingsActivity::class.java
                TYPE_MIERU -> MieruSettingsActivity::class.java
                TYPE_NAIVE -> NaiveSettingsActivity::class.java
                TYPE_HYSTERIA -> HysteriaSettingsActivity::class.java
                TYPE_SSH -> SSHSettingsActivity::class.java
                TYPE_WG -> WireGuardSettingsActivity::class.java
                TYPE_TUIC -> TuicSettingsActivity::class.java
                TYPE_SHADOWTLS -> ShadowTLSSettingsActivity::class.java
                TYPE_ANYTLS -> AnyTLSSettingsActivity::class.java
                TYPE_CHAIN -> ChainSettingsActivity::class.java
                TYPE_CONFIG -> ConfigSettingActivity::class.java
                else -> throw IllegalArgumentException()
            }
        ).apply {
            putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
            putExtra(ProfileSettingsActivity.EXTRA_IS_SUBSCRIPTION, isSubscription)
        }
    }

    data class LatencyCandidate(
        val id: Long,
        val status: Int,
        val ping: Int,
    )

    data class NodeListItem(
        val id: Long,
        val groupId: Long,
        val type: Int,
        val userOrder: Long,
        val status: Int,
        val ping: Int,
        val uuid: String,
        val error: String?,
        val displayNameCache: String,
        val displayAddressCache: String,
        val displayTypeCache: String,
        val hasExplicitName: Boolean,
        val configRevision: Long,
    ) {
        fun toStub(): ProxyEntity = ProxyEntity(
            id = id,
            groupId = groupId,
            type = type,
            userOrder = userOrder,
            status = status,
            ping = ping,
            uuid = uuid,
            error = error,
            displayNameCache = displayNameCache,
            displayAddressCache = displayAddressCache,
            displayTypeCache = displayTypeCache,
            hasExplicitName = hasExplicitName,
            configRevision = configRevision,
        )
    }

    data class ConfigRevisionRow(
        val id: Long,
        val groupId: Long,
        val type: Int,
        val configRevision: Long,
    )

    data class TestResultUpdate(
        val id: Long,
        val status: Int,
        val ping: Int,
        val error: String?,
    )

    data class OrderUpdate(
        val id: Long,
        val userOrder: Long,
    )

    @androidx.room.Dao
    interface Dao {

        @Query("select * from proxy_entities")
        fun getAll(): List<ProxyEntity>

        @Query(
            "SELECT id, groupId, type, userOrder, status, ping, uuid, error, " +
                "displayNameCache, displayAddressCache, displayTypeCache, hasExplicitName, " +
                "configRevision FROM proxy_entities " +
                "ORDER BY CASE WHEN status = 1 AND ping > 0 THEN 0 ELSE 1 END, " +
                "CASE WHEN status = 1 AND ping > 0 THEN ping ELSE 2147483647 END, id"
        )
        fun getNodeList(): List<NodeListItem>

        @Query(
            "SELECT id, groupId, type, userOrder, status, ping, uuid, error, " +
                "displayNameCache, displayAddressCache, displayTypeCache, hasExplicitName, " +
                "configRevision FROM proxy_entities WHERE groupId = :groupId " +
                "ORDER BY CASE WHEN status = 1 AND ping > 0 THEN 0 ELSE 1 END, " +
                "CASE WHEN status = 1 AND ping > 0 THEN ping ELSE 2147483647 END, userOrder"
        )
        fun getNodeListByGroup(groupId: Long): List<NodeListItem>

        @Query("SELECT COUNT(*) FROM proxy_entities")
        fun countAll(): Long

        @Query("SELECT id FROM proxy_entities")
        fun getAllIds(): List<Long>

        @Query(
            "SELECT id, status, ping FROM proxy_entities WHERE type != :excludedType " +
                "ORDER BY CASE WHEN id = :selectedId THEN 0 ELSE 1 END, " +
                "CASE WHEN status = 1 AND ping > 0 THEN 0 ELSE 1 END, " +
                "CASE WHEN status = 1 AND ping > 0 THEN ping ELSE 2147483647 END, id " +
                "LIMIT :limit"
        )
        fun getLatencyCandidates(
            excludedType: Int,
            selectedId: Long,
            limit: Int,
        ): List<LatencyCandidate>

        @Query("SELECT id FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getIdsByGroup(groupId: Long): List<Long>

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities WHERE id in (:proxyIds)")
        fun getEntities(proxyIds: List<Long>): List<ProxyEntity>

        @Query("SELECT COUNT(*) FROM proxy_entities WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT  MAX(userOrder) + 1 FROM proxy_entities WHERE groupId = :groupId")
        fun nextOrder(groupId: Long): Long?

        @Query("SELECT * FROM proxy_entities WHERE id = :proxyId")
        fun getById(proxyId: Long): ProxyEntity?

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyId)")
        fun deleteById(proxyId: Long): Int

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM proxy_entities WHERE groupId in (:groupId)")
        fun deleteByGroup(groupId: LongArray)

        @Delete
        fun deleteProxy(proxy: ProxyEntity): Int

        @Delete
        fun deleteProxy(proxies: List<ProxyEntity>): Int

        @Update
        fun updateProxyRaw(proxy: ProxyEntity): Int

        fun updateProxy(proxy: ProxyEntity): Int {
            if (proxy.displayNameCache.isBlank() || proxy.displayTypeCache.isBlank()) {
                proxy.refreshListMetadata()
            }
            return updateProxyRaw(proxy)
        }

        @Update
        fun updateProxyRaw(proxies: List<ProxyEntity>): Int

        fun updateProxy(proxies: List<ProxyEntity>): Int {
            proxies.forEach { proxy ->
                if (proxy.displayNameCache.isBlank() || proxy.displayTypeCache.isBlank()) {
                    proxy.refreshListMetadata()
                }
            }
            return updateProxyRaw(proxies)
        }

        @Query("SELECT id, groupId, type, configRevision FROM proxy_entities")
        fun getAllConfigRevisions(): List<ConfigRevisionRow>

        @Update(entity = ProxyEntity::class)
        fun updateTestResultRows(results: List<TestResultUpdate>): Int

        @Update(entity = ProxyEntity::class)
        fun updateOrders(results: List<OrderUpdate>): Int

        /**
         * Persist connection-test metadata without writing a stale in-memory entity over a
         * concurrently edited or subscription-refreshed server configuration.
         */
        @Transaction
        fun updateTestResultsIfUnchanged(results: List<ProxyEntity>): List<Long> {
            val revisions = getAllConfigRevisions().associateBy(ConfigRevisionRow::id)
            val accepted = results.filter { result ->
                val current = revisions[result.id]
                current != null && current.groupId == result.groupId &&
                    current.type == result.type && current.configRevision == result.configRevision
            }
            if (accepted.isEmpty()) return emptyList()
            updateTestResultRows(accepted.map { result ->
                TestResultUpdate(result.id, result.status, result.ping, result.error)
            })
            return accepted.map(ProxyEntity::id)
        }

        @Insert
        fun addProxyRaw(proxy: ProxyEntity): Long

        fun addProxy(proxy: ProxyEntity): Long {
            if (proxy.displayNameCache.isBlank() || proxy.displayTypeCache.isBlank()) {
                proxy.refreshListMetadata()
            }
            return addProxyRaw(proxy)
        }

        @Insert
        fun addProxiesRaw(proxies: List<ProxyEntity>): List<Long>

        fun addProxies(proxies: List<ProxyEntity>): List<Long> {
            proxies.forEach { proxy ->
                if (proxy.displayNameCache.isBlank() || proxy.displayTypeCache.isBlank()) {
                    proxy.refreshListMetadata()
                }
            }
            return addProxiesRaw(proxies)
        }

        @Transaction
        fun addProxyBatch(groupId: Long, proxies: List<ProxyEntity>): List<Long> {
            var order = nextOrder(groupId) ?: 1L
            proxies.forEach { entity ->
                entity.groupId = groupId
                entity.userOrder = order++
            }
            return addProxies(proxies)
        }

        @Insert
        fun insertRaw(proxies: List<ProxyEntity>)

        fun insert(proxies: List<ProxyEntity>) {
            proxies.forEach { proxy ->
                if (proxy.displayNameCache.isBlank() || proxy.displayTypeCache.isBlank()) {
                    proxy.refreshListMetadata()
                }
            }
            insertRaw(proxies)
        }

        @Transaction
        fun applySubscriptionChanges(
            additions: List<ProxyEntity>,
            updates: List<ProxyEntity>,
            deletions: List<ProxyEntity>,
        ) {
            if (additions.isNotEmpty()) insert(additions)
            if (updates.isNotEmpty()) updateProxy(updates)
            if (deletions.isNotEmpty()) deleteProxy(deletions)
        }

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteAll(groupId: Long): Int

        @Query("DELETE FROM proxy_entities")
        fun reset()

        @Query("DELETE FROM proxy_entities WHERE groupId NOT IN (SELECT id FROM proxy_groups)")
        fun deleteOrphans(): Int

    }

    override fun describeContents(): Int {
        return 0
    }
}
