package li.gkd.app.data

import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import li.gkd.app.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XAdSkipRuleUpdatePolicyTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val bytes = File("src/main/assets/${XAdSkipRulePack.UNIFIED_ASSET}").readBytes()

    private fun manifest() = XAdSkipRuleUpdatePolicy.Manifest(
        schemaVersion = 1, subscriptionId = XAdSkipRulePack.UNIFIED_ID, version = 1,
        minAppVersionCode = 7, url = "https://xadskip.com/rules/releases/1/rules.json",
        size = bytes.size,
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    private fun envelope(manifest: XAdSkipRuleUpdatePolicy.Manifest): ByteArray {
        val payload = json.encodeToString(manifest).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update(payload) }.sign()
        return json.encodeToString(XAdSkipRuleUpdatePolicy.Envelope(
            "SHA256withRSA", Base64.getEncoder().encodeToString(payload), Base64.getEncoder().encodeToString(signature),
        )).toByteArray()
    }

    @Test
    fun validSignatureAndHashAllowTheCurrentParserToAcceptTheUnifiedRules() {
        val verified = XAdSkipRuleUpdatePolicy.verifyManifest(envelope(manifest()), keys.public.encoded, 7)
        assertEquals(1, XAdSkipRuleUpdatePolicy.verifyRules(verified, bytes).version)
    }

    @Test
    fun tamperedPayloadCannotPassThePinnedPublicKeyCheck() {
        val signed = json.decodeFromString<XAdSkipRuleUpdatePolicy.Envelope>(envelope(manifest()).decodeToString())
        val changed = signed.copy(payload = Base64.getEncoder().encodeToString(
            json.encodeToString(manifest().copy(version = 999)).toByteArray()
        ))
        assertThrows(IllegalArgumentException::class.java) {
            XAdSkipRuleUpdatePolicy.verifyManifest(json.encodeToString(changed).toByteArray(), keys.public.encoded, 7)
        }
    }

    @Test
    fun signedForeignDownloadUrlAndIncompatibleAppVersionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            XAdSkipRuleUpdatePolicy.verifyManifest(envelope(manifest().copy(url = "https://example.com/rules.json")), keys.public.encoded, 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            XAdSkipRuleUpdatePolicy.verifyManifest(envelope(manifest().copy(minAppVersionCode = 8)), keys.public.encoded, 7)
        }
    }

    @Test
    fun changedRuleBytesAreRejectedBeforeTheyCanReplaceActiveRules() {
        val changed = bytes.copyOf().also { it[it.lastIndex] = 32 }
        assertThrows(IllegalArgumentException::class.java) { XAdSkipRuleUpdatePolicy.verifyRules(manifest(), changed) }
    }
}
