package li.gkd.app.data

import li.gkd.db.SubsAppConfig
import li.gkd.db.SubsAppGroupConfig
import li.gkd.db.SubsCategoryConfig
import li.gkd.db.SubsGlobalGroupConfig
import li.gkd.db.SubsItem
import li.gkd.db.SubscriptionConfigSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XAdSkipUnifiedMigrationTest {
    private fun original(id: Long) = RawSubscription.parse(
        """{id:$id,name:'source',version:1,categories:[{key:2,name:'开屏广告'}],
          apps:[{id:'example.app',groups:[{key:4,name:'开屏广告',rules:'[text="跳过"]'}]}],
          globalGroups:[{key:8,name:'全局测试',rules:[{matches:'[text="跳过"]'}]}]}"""
    )

    @Test
    fun disabledSubscriptionAndApplicationRemainDisabledAndOldRecordsAreArchived() {
        val old = original(11)
        val configs = SubscriptionConfigSnapshot(
            subsItems = listOf(SubsItem(11, order = 2, enable = false), SubsItem(-2, order = 0, enable = true)),
            appConfigs = listOf(SubsAppConfig(false, 11, "example.app")),
            appGroupConfigs = listOf(SubsAppGroupConfig(11, "example.app", 4, false, "example.app.Screen")),
        )
        val plan = XAdSkipUnifiedMigration.prepare(configs, mapOf(11L to old), listOf(
            XAdSkipUnifiedMigration.GroupMapping(11, "example.app", 4, 99),
        ))
        assertFalse(plan.item.enable)
        assertEquals(listOf(11L), plan.archivedItems.map { it.id })
        assertFalse(plan.appConfigs.single().enable)
        assertEquals(false, plan.appGroupConfigs.single().enable)
        assertEquals("example.app.Screen", plan.appGroupConfigs.single().exclude)
        assertEquals(99, plan.appGroupConfigs.single().groupKey)
        // Inputs and the unrelated local subscription are never rewritten.
        assertTrue(configs.subsItems.last().enable)
        assertEquals(4, configs.appGroupConfigs.single().groupKey)
    }

    @Test
    fun categoryDisableAndExplicitDisableWinWhenDuplicateGroupsMerge() {
        val configs = SubscriptionConfigSnapshot(
            subsItems = listOf(SubsItem(11, order = 0, enable = true), SubsItem(12, order = 1, enable = true)),
            categoryConfigs = listOf(SubsCategoryConfig(false, 11, 2)),
            appGroupConfigs = listOf(SubsAppGroupConfig(12, "example.app", 4, true, "example.app.Second")),
        )
        val plan = XAdSkipUnifiedMigration.prepare(configs, mapOf(11L to original(11), 12L to original(12)), listOf(
            XAdSkipUnifiedMigration.GroupMapping(11, "example.app", 4, 99),
            XAdSkipUnifiedMigration.GroupMapping(12, "example.app", 4, 99),
        ))
        assertTrue(plan.item.enable)
        assertEquals(false, plan.appGroupConfigs.single().enable)
        assertEquals("example.app.Second", plan.appGroupConfigs.single().exclude)
    }

    @Test
    fun globalExclusionsAreUnionedAndAnExclusionWinsOverAnInclusion() {
        val configs = SubscriptionConfigSnapshot(
            subsItems = listOf(SubsItem(11, order = 0, enable = true), SubsItem(12, order = 1, enable = true)),
            globalGroupConfigs = listOf(
                SubsGlobalGroupConfig(11, 8, true, "example.app\nother.app/other.app.Screen"),
                SubsGlobalGroupConfig(12, 8, true, "!example.app\nthird.app"),
            ),
        )
        val plan = XAdSkipUnifiedMigration.prepare(configs, mapOf(11L to original(11), 12L to original(12)), listOf(
            XAdSkipUnifiedMigration.GroupMapping(11, null, 8, 99),
            XAdSkipUnifiedMigration.GroupMapping(12, null, 8, 99),
        ))
        val exclusions = ExcludeData.parse(plan.globalGroupConfigs.single().exclude)
        assertTrue("example.app" in exclusions.excludeAppIds)
        assertTrue("third.app" in exclusions.excludeAppIds)
        assertTrue("other.app" to "other.app.Screen" in exclusions.activityIds)
    }

    @Test
    fun overlapCandidateIsNotEnabledByMigration() {
        val plan = XAdSkipUnifiedMigration.prepare(
            SubscriptionConfigSnapshot(subsItems = listOf(SubsItem(11, order = 0, enable = true))),
            mapOf(11L to original(11)),
            listOf(XAdSkipUnifiedMigration.GroupMapping(11, "example.app", 4, 99, conflictDisabled = true)),
        )
        assertEquals(false, plan.appGroupConfigs.single().enable)
    }

    @Test
    fun existingSecondaryFallbackKeepsItsExclusionsWithoutIntroducingThePrimary() {
        val plan = XAdSkipUnifiedMigration.prepare(
            SubscriptionConfigSnapshot(
                subsItems = listOf(SubsItem(12, order = 0, enable = true)),
                globalGroupConfigs = listOf(SubsGlobalGroupConfig(12, 8, true, "com.bank")),
            ),
            mapOf(12L to original(12)),
            listOf(
                XAdSkipUnifiedMigration.GroupMapping(11, null, 8, 98),
                XAdSkipUnifiedMigration.GroupMapping(12, null, 8, 99, conflictDisabled = true, conflictsWith = listOf(98)),
            ),
        )
        val groups = plan.globalGroupConfigs.associateBy { it.groupKey }
        assertEquals(false, groups.getValue(98).enable)
        assertEquals(true, groups.getValue(99).enable)
        assertTrue("com.bank" in ExcludeData.parse(groups.getValue(99).exclude).excludeAppIds)
    }

    @Test
    fun explicitGroupEnableKeepsPrecedenceOverCategoryDisable() {
        val plan = XAdSkipUnifiedMigration.prepare(
            SubscriptionConfigSnapshot(
                subsItems = listOf(SubsItem(11, order = 0, enable = true)),
                categoryConfigs = listOf(SubsCategoryConfig(false, 11, 2)),
                appGroupConfigs = listOf(SubsAppGroupConfig(11, "example.app", 4, true)),
            ), mapOf(11L to original(11)),
            listOf(XAdSkipUnifiedMigration.GroupMapping(11, "example.app", 4, 99)),
        )
        assertEquals(true, plan.appGroupConfigs.single().enable)
    }

    @Test
    fun editedPackagedRulesAreDetectedBeforeMigrationOrAutomaticUpdate() {
        val baseline = original(11)
        val edited = baseline.copy(apps = baseline.apps.map { it.copy(groups = emptyList()) })
        assertTrue(XAdSkipRulePack.matchesPackagedContent(baseline, original(11)))
        assertFalse(XAdSkipRulePack.matchesPackagedContent(edited, baseline))
    }
}
