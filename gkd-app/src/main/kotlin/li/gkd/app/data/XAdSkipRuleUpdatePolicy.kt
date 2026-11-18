package li.gkd.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import li.gkd.app.util.json
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Public-key verification is independent of networking and Android lifecycle. */
object XAdSkipRuleUpdatePolicy {
    const val MANIFEST_URL = "https://xadskip.com/rules/stable/manifest.json"
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_RULE_BYTES = 8 * 1024 * 1024

    @Serializable
    data class Envelope(val algorithm: String, val payload: String, val signature: String)

    @Serializable
    data class Manifest(
        val schemaVersion: Int,
        val subscriptionId: Long,
        val version: Int,
        val minAppVersionCode: Int,
        val url: String,
        val size: Int,
        val sha256: String,
    )

    fun verifyManifest(bytes: ByteArray, publicKey: ByteArray, appVersionCode: Int): Manifest {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "规则更新清单过大" }
        val envelope = json.decodeFromString<Envelope>(bytes.decodeToString(throwOnInvalidSequence = true))
        require(envelope.algorithm == "SHA256withRSA") { "不支持的规则签名格式" }
        val payload = Base64.getDecoder().decode(envelope.payload)
        require(payload.size <= MAX_MANIFEST_BYTES / 2) { "规则清单内容过大" }
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(payload)
        require(verifier.verify(Base64.getDecoder().decode(envelope.signature))) { "规则签名校验失败" }
        val manifest = json.decodeFromString<Manifest>(payload.decodeToString(throwOnInvalidSequence = true))
        require(manifest.schemaVersion == 1) { "规则格式需要新版应用" }
        require(manifest.subscriptionId == XAdSkipRulePack.UNIFIED_ID && manifest.version > 0) { "规则库标识或版本不匹配" }
        require(manifest.minAppVersionCode in 1..appVersionCode) { "请先更新应用，再获取这版规则" }
        require(manifest.size in 1..MAX_RULE_BYTES) { "规则文件大小无效" }
        require(manifest.sha256.matches(Regex("[a-f0-9]{64}"))) { "规则校验码无效" }
        val uri = URI(manifest.url)
        require(uri.scheme == "https" && uri.host == "xadskip.com" && uri.port == -1 &&
            uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
            uri.rawPath == "/rules/releases/${manifest.version}/rules.json") { "规则下载地址不属于本站版本目录" }
        return manifest
    }

    fun verifyRules(manifest: Manifest, bytes: ByteArray): RawSubscription {
        require(bytes.size == manifest.size && bytes.size <= MAX_RULE_BYTES) { "规则文件大小与清单不一致" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == manifest.sha256) { "规则文件校验失败" }
        val source = bytes.decodeToString(throwOnInvalidSequence = true)
        val metadata = json.parseToJsonElement(source).jsonObject.getValue("xadskip").jsonObject
        require(metadata.getValue("schemaVersion").jsonPrimitive.int == 1) { "规则来源格式不兼容" }
        val subscription = XAdSkipRulePack.prepareUnified(source)
        require(subscription.version == manifest.version) { "规则版本与清单不一致" }
        validateSubscription(subscription)
        return subscription
    }

    fun validateSubscription(subscription: RawSubscription) {
        require(subscription.apps.size <= 5000 && subscription.groupsSize <= 15000) { "规则数量超过支持范围" }
        require(subscription.apps.map { it.id }.distinct().size == subscription.apps.size) { "规则包含重复应用" }
        val allGroups = subscription.apps.flatMap { it.groups } + subscription.globalGroups
        require(allGroups.sumOf { it.rules.size } <= 30000) { "规则条目过多" }
        subscription.apps.forEach { app ->
            require(app.groups.map { it.key }.distinct().size == app.groups.size) { "应用规则标识重复：${app.id}" }
            val keys = app.groups.map { it.key }.toSet()
            app.groups.forEach { group ->
                require(group.scopeKeys.orEmpty().all { it in keys }) { "规则作用域依赖缺失：${app.id}" }
            }
        }
        val globalKeys = subscription.globalGroups.map { it.key }.toSet()
        require(globalKeys.size == subscription.globalGroups.size) { "全局规则标识重复" }
        subscription.globalGroups.forEach { group ->
            require(group.scopeKeys.orEmpty().all { it in globalKeys }) { "全局规则作用域依赖缺失" }
        }
        allGroups.forEach { group -> require(group.valid) { "规则无法使用：${group.name}；${group.errorDesc}" } }
    }

    /** Persisted switches refer to these keys, so publishers may not retarget them. */
    fun verifyMappingContinuity(previousSource: String, updatedSource: String) {
        fun identity(mapping: XAdSkipUnifiedMigration.GroupMapping) =
            Triple(mapping.sourceId, mapping.appId, mapping.sourceGroupKey)
        val previous = XAdSkipUnifiedMigration.mappings(previousSource).associateBy(::identity)
        XAdSkipUnifiedMigration.mappings(updatedSource).forEach { mapping ->
            previous[identity(mapping)]?.let { old ->
                require(old.unifiedGroupKey == mapping.unifiedGroupKey) { "规则标识变更需要显式配置迁移" }
            }
        }
    }
}
