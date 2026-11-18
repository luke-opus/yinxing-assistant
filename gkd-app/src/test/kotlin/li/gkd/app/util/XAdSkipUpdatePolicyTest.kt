package li.gkd.app.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XAdSkipUpdatePolicyTest {
    private val release = NewVersion(
        packageName = "app.xadskip.android",
        versionCode = 6,
        versionName = "0.3.3",
        downloadUrl = "https://xadskip.com/downloads/XAdSkip-0.3.3.apk",
        fileSize = 5,
        sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
    )

    @Test
    fun updateUsesIncreasingCodeAndNeverOffersDowngrade() {
        assertTrue(XAdSkipUpdatePolicy.isNewer(release, 5))
        assertFalse(XAdSkipUpdatePolicy.isNewer(release, 6))
        assertFalse(XAdSkipUpdatePolicy.isNewer(release, 7))
    }

    @Test
    fun manifestProtocolDecodesAndResolvesOfficialRelativeDownload() {
        val decoded = Json.decodeFromString<NewVersion>(
            """{"packageName":"app.xadskip.android","versionCode":6,"versionName":"0.3.3","downloadUrl":"/downloads/XAdSkip-0.3.3.apk","fileSize":5,"sha256":"${release.sha256}"}"""
        )
        assertEquals(release.downloadUrl, XAdSkipUpdatePolicy.downloadUrl(decoded))
        assertTrue(XAdSkipUpdatePolicy.isNewer(decoded, 5))
    }

    @Test
    fun rejectsUpstreamOtherHostAndUntrustedDownloadLocations() {
        listOf(
            "http://xadskip.com/downloads/XAdSkip-0.3.3.apk",
            "https://github.com/gkd-kit/gkd/downloads/app.apk",
            "https://xadskip.com.evil.example/downloads/app.apk",
            "https://user@xadskip.com/downloads/app.apk",
            "https://xadskip.com:444/downloads/app.apk",
            "https://xadskip.com/private/app.apk",
            "https://xadskip.com/downloads/../private/app.apk",
            "https://xadskip.com/downloads/%2e%2e/private/app.apk",
            "https://xadskip.com/downloads/app.apk?redirect=elsewhere",
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) {
                XAdSkipUpdatePolicy.validate(release.copy(downloadUrl = url))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            XAdSkipUpdatePolicy.validate(release.copy(packageName = "li.songe.gkd"))
        }
    }

    @Test
    fun malformedManifestCannotBecomeLatestVersionResult() {
        listOf(
            release.copy(fileSize = 0),
            release.copy(sha256 = "not-a-checksum"),
            release.copy(versionName = ""),
            release.copy(versionCode = 0),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                XAdSkipUpdatePolicy.isNewer(invalid, 6)
            }
        }
    }

    @Test
    fun downloadMustMatchBothLengthAndDigest() {
        val file = File.createTempFile("xadskip-update-", ".apk")
        try {
            file.writeText("hello")
            XAdSkipUpdatePolicy.verifyDownload(release, file)
            file.writeText("hell")
            assertThrows(IllegalArgumentException::class.java) {
                XAdSkipUpdatePolicy.verifyDownload(release, file)
            }
            file.writeText("wrong")
            assertThrows(IllegalArgumentException::class.java) {
                XAdSkipUpdatePolicy.verifyDownload(release, file)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun foregroundChecksRespectDisabledSettingAndThrottleBothSuccessAndFailureAttempts() {
        val now = 200_000_000L
        assertFalse(XAdSkipUpdatePolicy.shouldAutoCheck(false, 0, now))
        assertTrue(XAdSkipUpdatePolicy.shouldAutoCheck(true, 0, now))
        assertFalse(XAdSkipUpdatePolicy.shouldAutoCheck(true, now - 10, now))
        assertFalse(XAdSkipUpdatePolicy.shouldAutoCheck(true, now - 899_999, now))
        assertTrue(XAdSkipUpdatePolicy.shouldAutoCheck(true, now - 900_000, now))
        assertTrue(XAdSkipUpdatePolicy.shouldAutoCheck(true, now + 10, now))
    }

    @Test
    fun restoresOnlyTrustedNewerVersionsAndRespectsIgnoredVersions() {
        assertEquals(release, XAdSkipUpdatePolicy.availableVersion(release, 5, emptySet()))
        assertNull(XAdSkipUpdatePolicy.availableVersion(release, 6, emptySet()))
        assertNull(XAdSkipUpdatePolicy.availableVersion(release, 7, emptySet()))
        assertNull(XAdSkipUpdatePolicy.availableVersion(release, 5, setOf(6)))
        assertNull(XAdSkipUpdatePolicy.availableVersion(null, 5, emptySet()))
        assertNull(XAdSkipUpdatePolicy.availableVersion(
            release.copy(downloadUrl = "https://example.com/untrusted.apk"), 5, emptySet(),
        ))
        assertNull(XAdSkipUpdatePolicy.availableVersion(
            release.copy(sha256 = "corrupt-cache"), 5, emptySet(),
        ))
    }
}
