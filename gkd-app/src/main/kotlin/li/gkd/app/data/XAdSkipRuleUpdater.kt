package li.gkd.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import li.gkd.app.META
import li.gkd.app.app
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.util.LogUtils
import li.gkd.db.Db
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Explicit foreground-open event; no timer, polling watcher, or push SDK. */
object XAdSkipRuleUpdater {
    data class Status(
        val checking: Boolean = false,
        val version: Int? = null,
        val lastCheckedAt: Long? = null,
        val message: String? = null,
    )

    val statusFlow: StateFlow<Status>
        field = MutableStateFlow(Status())
    private val checkMutex = Mutex()
    private var lastAttemptAt = 0L

    suspend fun checkOnAppOpen(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!checkMutex.tryLock()) return@withContext
        try {
            val now = System.currentTimeMillis()
            // Coalesce rapid resumes, while each normal return to the app can check.
            if (!force && now >= lastAttemptAt && now - lastAttemptAt < 60_000L) return@withContext
            lastAttemptAt = now
            val snapshot = SubscriptionRepository.awaitSnapshot()
            val current = snapshot.subscriptions[XAdSkipRulePack.UNIFIED_ID] ?: return@withContext
            val item = Db.subsItemDao.queryAll().find { it.id == current.id } ?: return@withContext
            if (!item.enableUpdate) return@withContext
            statusFlow.value = Status(checking = true, version = current.version)
            val bundledSource = readAsset(XAdSkipRulePack.UNIFIED_ASSET)
            val bundled = XAdSkipRulePack.prepareUnified(bundledSource)
            XAdSkipApprovedRuleStore.remember(bundled.version, bundledSource.toByteArray())
            val baselineSource = if (current.version == bundled.version) bundledSource else
                XAdSkipApprovedRuleStore.read(current.version)
            val baseline = baselineSource?.let(XAdSkipRulePack::prepareUnified)
            if (baseline == null || !XAdSkipRulePack.matchesPackagedContent(current, baseline)) {
                statusFlow.value = Status(version = current.version, message = "检测到自定义规则，已保留当前内容")
                return@withContext
            }
            val publicKey = app.assets.open("xadskip/unified/release-public.der").use { it.readBytes() }
            val manifest = XAdSkipRuleUpdatePolicy.verifyManifest(
                download(XAdSkipRuleUpdatePolicy.MANIFEST_URL, XAdSkipRuleUpdatePolicy.MAX_MANIFEST_BYTES),
                publicKey, META.versionCode,
            )
            if (manifest.version <= current.version) {
                statusFlow.value = Status(version = current.version, lastCheckedAt = now, message = "规则已是本站当前版本")
                return@withContext
            }
            val bytes = download(manifest.url, manifest.size)
            val subscription = XAdSkipRuleUpdatePolicy.verifyRules(manifest, bytes)
            XAdSkipRuleUpdatePolicy.verifyMappingContinuity(requireNotNull(baselineSource), bytes.decodeToString())
            currentCoroutineContext().ensureActive()
            // Write immutable provenance before switching; the old active file is
            // untouched on download, signature, parsing, or persistence failure.
            XAdSkipApprovedRuleStore.remember(manifest.version, bytes)
            val applied = SubscriptionRepository.applyVerifiedUnifiedUpdate(subscription, current)
            statusFlow.value = Status(
                version = if (applied) subscription.version else current.version,
                lastCheckedAt = now,
                message = if (applied) "规则已更新" else "当前配置已改变，本次保留原规则",
            )
            if (applied) XAdSkipApprovedRuleStore.prune(setOf(current.version, subscription.version))
        } catch (e: CancellationException) {
            statusFlow.value = statusFlow.value.copy(checking = false)
            throw e
        } catch (e: Exception) {
            LogUtils.d("规则检查失败，继续使用原规则", e)
            statusFlow.value = statusFlow.value.copy(checking = false, message = "暂未更新，继续使用原规则")
        } finally {
            checkMutex.unlock()
        }
    }

    private fun readAsset(path: String): String = app.assets.open(path).bufferedReader().use { it.readText() }

    private suspend fun download(url: String, maxBytes: Int): ByteArray {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            require(connection.responseCode == 200) { "规则服务暂不可用" }
            require(connection.contentLength <= maxBytes) { "规则响应超过大小限制" }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "规则响应超过大小限制" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
