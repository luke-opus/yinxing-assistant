package li.gkd.app.data

import li.gkd.app.app
import li.gkd.app.util.LogUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Private immutable baselines, supplied only from packaged assets or verified releases.
 * Never pass the active editable subscription here to establish its own trust baseline.
 */
object XAdSkipApprovedRuleStore {
    private val directory: File
        get() = app.filesDir.resolve("xadskip-verified-rules")

    @Synchronized
    fun remember(version: Int, bytes: ByteArray) {
        require(version > 0) { "规则版本无效" }
        require(bytes.isNotEmpty() && bytes.size <= XAdSkipRuleUpdatePolicy.MAX_RULE_BYTES) {
            "规则来源大小无效"
        }
        val folder = directory
        check(folder.isDirectory || folder.mkdirs()) { "无法创建规则来源目录" }
        val destination = folder.resolve("$version.json")
        if (destination.exists()) {
            // Publishing different content under the same version would invalidate
            // edit detection. Keep the first approved baseline instead of replacing it.
            check(destination.length() == bytes.size.toLong() &&
                destination.readBytes().contentEquals(bytes)) { "同一规则版本的来源内容不一致" }
            return
        }
        val temporary = File.createTempFile("rules-$version-", ".pending", folder)
        try {
            temporary.outputStream().use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun read(version: Int): String? {
        require(version > 0) { "规则版本无效" }
        val file = directory.resolve("$version.json")
        if (!file.exists()) return null
        if (!file.isFile || file.length() !in 1..XAdSkipRuleUpdatePolicy.MAX_RULE_BYTES.toLong()) {
            throw IOException("已确认规则来源损坏")
        }
        val bytes = file.readBytes()
        require(bytes.size <= XAdSkipRuleUpdatePolicy.MAX_RULE_BYTES) { "规则来源过大" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    /** Pruning is best-effort and cannot turn an installed update into a reported failure. */
    @Synchronized
    fun prune(keepVersions: Set<Int>) {
        runCatching {
            directory.listFiles().orEmpty().forEach { file ->
                if (!file.isFile || file.extension != "json") return@forEach
                val name = file.nameWithoutExtension
                if (name.isEmpty() || name.any { it !in '0'..'9' }) return@forEach
                val version = name.toIntOrNull() ?: return@forEach
                if (version !in keepVersions && !file.delete()) {
                    runCatching { LogUtils.d("旧规则来源清理失败", file.name) }
                }
            }
        }.onFailure { error ->
            runCatching { LogUtils.d("旧规则来源清理失败", error) }
        }
    }
}
