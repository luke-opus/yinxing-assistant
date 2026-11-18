package li.gkd.app.util

import li.gkd.app.data.XAdSkipConfig
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** The public manifest is an app-specific protocol, independent of upstream releases. */
object XAdSkipUpdatePolicy {
    private const val packageName = "app.xadskip.android"
    private val digestPattern = Regex("[0-9a-fA-F]{64}")

    fun validate(version: NewVersion): NewVersion {
        require(version.packageName == packageName) { "更新信息不是 XAdSkip 安装包" }
        require(version.versionCode > 0 && version.versionName.isNotBlank()) { "更新版本信息无效" }
        require(version.fileSize > 0) { "更新文件大小无效" }
        require(digestPattern.matches(version.sha256)) { "更新文件校验信息无效" }
        downloadUrl(version)
        return version
    }

    fun isNewer(version: NewVersion, installedCode: Int): Boolean =
        validate(version).versionCode > installedCode

    fun availableVersion(
        version: NewVersion?,
        installedCode: Int,
        ignoredCodes: Set<Int>,
    ): NewVersion? = version?.takeIf {
        runCatching { isNewer(it, installedCode) && it.versionCode !in ignoredCodes }
            .getOrDefault(false)
    }

    fun downloadUrl(version: NewVersion): String {
        val uri = URI(XAdSkipConfig.updateManifestUrl).resolve(version.downloadUrl)
        require(
            uri.scheme == "https" && uri.host == "xadskip.com" &&
                uri.port == -1 && uri.userInfo == null && uri.query == null &&
                uri.fragment == null && uri.path.startsWith("/downloads/") &&
                uri.path.endsWith(".apk") && uri.rawPath == uri.path && uri.normalize() == uri
        ) { "更新地址必须是 XAdSkip 官网的安装包" }
        return uri.toASCIIString()
    }

    fun shouldAutoCheck(enabled: Boolean, lastCheckedAt: Long, now: Long): Boolean =
        enabled && (lastCheckedAt <= 0 || now < lastCheckedAt || now - lastCheckedAt >= 900_000L)

    fun verifyDownload(version: NewVersion, file: File) {
        validate(version)
        require(file.length() == version.fileSize) { "安装包下载不完整，请重试" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(version.sha256, ignoreCase = true)) {
            "安装包校验失败，请重新下载"
        }
    }
}
