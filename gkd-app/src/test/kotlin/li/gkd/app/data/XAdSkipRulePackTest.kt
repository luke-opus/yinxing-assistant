package li.gkd.app.data

import java.io.File
import li.gkd.db.LOCAL_SUBS_ID
import li.gkd.db.SubsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XAdSkipRulePackTest {
    private fun source(pack: XAdSkipRulePack.Community) =
        File("src/main/assets/${pack.assetPath}").readText()

    @Test
    fun newInstallationReceivesAnEnabledOfflineSubscription() {
        val imported = requireNotNull(
            XAdSkipRulePack.prepareFirstRunImport(false, emptyList()) { path ->
                File("src/main/assets/$path").readText()
            }
        )
        assertTrue(imported.item.enable)
        assertTrue(imported.item.enableUpdate)
        assertNull(imported.item.updateUrl)
        assertNull(imported.subscription.updateUrl)
        assertNull(imported.subscription.checkUpdateUrl)
        assertEquals(imported.item.id, imported.subscription.id)
        assertTrue(imported.subscription.hasRule)

        val original = XAdSkipRulePack.prepareUnified(File("src/main/assets/${XAdSkipRulePack.UNIFIED_ASSET}").readText())
        assertEquals(XAdSkipRulePack.UNIFIED_ID, imported.item.id)
        assertEquals(original.apps, imported.subscription.apps)
        assertEquals(original.globalGroups, imported.subscription.globalGroups)
        assertTrue(imported.subscription.appGroups.any { it.enable == false })
        XAdSkipRuleUpdatePolicy.validateSubscription(imported.subscription)
    }

    @Test
    fun completedInitializationDoesNotRecreateADeletedSubscription() {
        val imported = XAdSkipRulePack.prepareFirstRunImport(true, emptyList()) {
            error("An intentionally empty subscription list must remain empty")
        }
        assertNull(imported)
    }

    @Test
    fun newInstallationImportsOneUnifiedSourceInsteadOfChoosingAnUpstream() {
        val reads = mutableListOf<String>()
        val imported = requireNotNull(XAdSkipRulePack.prepareFirstRunImport(false, emptyList()) { path ->
            reads += path
            """{"id":2026111801,"name":"统一测试","version":1,"apps":[{"id":"example.app","groups":[{"key":1,"name":"开屏广告","rules":"[text=\"跳过\"]"}]}]}"""
        })
        assertEquals(listOf(XAdSkipRulePack.UNIFIED_ASSET), reads)
        assertEquals(XAdSkipRulePack.UNIFIED_ID, imported.subscription.id)
        assertTrue(imported.item.enable)
    }

    @Test
    fun upgradingAnExistingInstallationPreservesItsSubscriptionsAndDisabledState() {
        val existingConfigurations = listOf(
            listOf(SubsItem(id = LOCAL_SUBS_ID, order = 0)),
            listOf(SubsItem(id = XAdSkipRulePack.Community.GANLIN.subscriptionId, order = 0, enable = false)),
            listOf(SubsItem(id = 42, order = 3, enable = true, updateUrl = "https://example.com/rules.json")),
        )
        existingConfigurations.forEach { items ->
            assertNull(XAdSkipRulePack.prepareFirstRunImport(false, items) {
                error("Existing subscriptions must not be overwritten or supplemented")
            })
        }
    }

    @Test
    fun bundledRulesAreAcceptedByTheCurrentEngine() {
        XAdSkipRulePack.Community.entries.forEach { pack ->
            val subscription = XAdSkipRulePack.prepareCommunity(source(pack), pack)
            assertTrue(subscription.hasRule)
            subscription.apps.forEach { app ->
                app.groups.forEach { group ->
                    assertNull("${pack.title}/${app.id}/${group.key}", group.errorDesc)
                }
            }
            subscription.globalGroups.forEach { group ->
                assertNull("${pack.title}/global/${group.key}", group.errorDesc)
            }
        }
    }

    @Test
    fun offlineImportPreservesRulesWithoutReplacingRemoteSubscriptions() {
        XAdSkipRulePack.Community.entries.forEach { pack ->
            val text = source(pack)
            val original = RawSubscription.parse(text)
            val imported = XAdSkipRulePack.prepareCommunity(text, pack)
            assertNotEquals(original.id, imported.id)
            assertNotEquals(XAdSkipConfig.draftSubscriptionId, imported.id)
            assertNull(imported.updateUrl)
            assertNull(imported.checkUpdateUrl)
            assertEquals(original.author, imported.author)
            assertEquals(original.version, imported.version)
            assertEquals(original.apps, imported.apps)
            assertEquals(original.globalGroups, imported.globalGroups)
            assertEquals(original.categories, imported.categories)
        }
        // These IDs persist in user databases; the two bundled sources must never collide.
        assertNotEquals(
            XAdSkipRulePack.Community.GANLIN.subscriptionId,
            XAdSkipRulePack.Community.AOGUAI.subscriptionId,
        )
    }

    @Test
    fun incorrectlyAssignedSourceIsRejectedBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) {
            XAdSkipRulePack.prepareCommunity(
                source(XAdSkipRulePack.Community.GANLIN),
                XAdSkipRulePack.Community.AOGUAI,
            )
        }
    }

    @Test
    fun existingOfflineCopyReceivesNewBundledVersionAfterFirstRun() {
        XAdSkipRulePack.Community.entries.forEach { pack ->
            val bundled = XAdSkipRulePack.prepareCommunity(source(pack), pack)
            val previous = bundled.copy(version = bundled.version - 1)
            val upgraded = requireNotNull(XAdSkipRulePack.prepareBundledUpgrade(previous) { source(pack) })
            assertEquals(previous.id, upgraded.id)
            assertEquals(bundled.version, upgraded.version)
            assertEquals(bundled.apps, upgraded.apps)
            assertEquals(bundled.globalGroups, upgraded.globalGroups)
            assertNull(upgraded.updateUrl)
        }
    }

    @Test
    fun equalOrNewerOfflineCopyIsNotRewrittenOrDowngraded() {
        val pack = XAdSkipRulePack.Community.GANLIN
        val bundled = XAdSkipRulePack.prepareCommunity(source(pack), pack)
        listOf(bundled, bundled.copy(version = bundled.version + 1)).forEach { existing ->
            assertNull(XAdSkipRulePack.prepareBundledUpgrade(existing) { source(pack) })
        }
    }

    @Test
    fun bundledUpgradeNeverTouchesLocalDraftOrUserAddedSubscriptions() {
        listOf(LOCAL_SUBS_ID, XAdSkipConfig.draftSubscriptionId, 42L).forEach { id ->
            val custom = RawSubscription(id = id, name = "自定义规则", version = 0)
            assertNull(XAdSkipRulePack.prepareBundledUpgrade(custom) {
                error("A user subscription must not read or be replaced by a bundled asset")
            })
        }
    }
}
