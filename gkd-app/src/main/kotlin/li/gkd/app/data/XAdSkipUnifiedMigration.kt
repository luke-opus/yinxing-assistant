package li.gkd.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import li.gkd.app.domain.rule.RuleGroupPolicy
import li.gkd.app.util.json
import li.gkd.db.SubsAppConfig
import li.gkd.db.SubsAppGroupConfig
import li.gkd.db.SubsGlobalGroupConfig
import li.gkd.db.SubsItem
import li.gkd.db.SubscriptionConfigSnapshot

/** Maps existing choices to stable unified keys without deleting source archives. */
object XAdSkipUnifiedMigration {
    @Serializable
    data class GroupMapping(
        val sourceId: Long,
        val appId: String? = null,
        val sourceGroupKey: Int,
        val unifiedGroupKey: Int,
        val conflictDisabled: Boolean = false,
        val conflictsWith: List<Int> = emptyList(),
    )

    @Serializable
    data class Metadata(val schemaVersion: Int, val groupMappings: List<GroupMapping>)

    data class Plan(
        val previousConfigs: SubscriptionConfigSnapshot,
        val item: SubsItem,
        val archivedItems: List<SubsItem>,
        val appConfigs: List<SubsAppConfig>,
        val appGroupConfigs: List<SubsAppGroupConfig>,
        val globalGroupConfigs: List<SubsGlobalGroupConfig>,
    )

    fun mappings(source: String): List<GroupMapping> {
        val element = json.parseToJsonElement(source).jsonObject.getValue("xadskip")
        val metadata = json.decodeFromString<Metadata>(element.toString())
        require(metadata.schemaVersion == 1) { "统一规则映射格式不兼容" }
        return metadata.groupMappings
    }

    fun prepare(
        configs: SubscriptionConfigSnapshot,
        originals: Map<Long, RawSubscription>,
        mappings: List<GroupMapping>,
    ): Plan {
        require(originals.isNotEmpty())
        val items = configs.subsItems.filter { it.id in originals }.associateBy { it.id }
        require(items.size == originals.size)
        val appConfigs = configs.appConfigs.filter { it.subsId in originals }
        val appGroupConfigs = configs.appGroupConfigs.associateBy { Triple(it.subsId, it.appId, it.groupKey) }
        val globalConfigs = configs.globalGroupConfigs.associateBy { it.subsId to it.groupKey }
        val categoryConfigs = configs.categoryConfigs.associateBy { it.subsId to it.categoryKey }
        val targetAppGroups = mutableListOf<SubsAppGroupConfig>()
        val targetGlobalGroups = mutableListOf<SubsGlobalGroupConfig>()
        mappings.groupBy { it.appId to it.unifiedGroupKey }
            .forEach { (target, sources) ->
                val enabled = mutableListOf<Boolean>()
                val exclusions = mutableListOf<String>()
                var explicitlyDisabled = false
                sources.filter { it.sourceId in originals }.forEach sourceLoop@{ mapping ->
                    val subscription = originals.getValue(mapping.sourceId)
                    val item = items.getValue(mapping.sourceId)
                    if (mapping.appId != null) {
                        val group = subscription.getAppGroups(mapping.appId)
                            .find { it.key == mapping.sourceGroupKey } ?: return@sourceLoop
                        val override = appGroupConfigs[Triple(mapping.sourceId, mapping.appId, mapping.sourceGroupKey)]
                        val category = subscription.getCategory(group.name)
                        val categoryOverride = category?.let { categoryConfigs[mapping.sourceId to it.key] }
                        explicitlyDisabled = explicitlyDisabled || (override?.enable ?: categoryOverride?.enable) == false
                        enabled += item.enable && RuleGroupPolicy.getGroupEnabled(group, override, category, categoryOverride)
                        override?.exclude?.let(exclusions::add)
                    } else {
                        val group = subscription.globalGroups.find { it.key == mapping.sourceGroupKey } ?: return@sourceLoop
                        val override = globalConfigs[mapping.sourceId to mapping.sourceGroupKey]
                        explicitlyDisabled = explicitlyDisabled || override?.enable == false
                        enabled += item.enable && RuleGroupPolicy.getGroupEnabled(group, override)
                        override?.exclude?.let(exclusions::add)
                    }
                }
                run {
                    val isEnabled = !explicitlyDisabled && enabled.any { it }
                    val appId = target.first
                    if (appId != null) {
                        targetAppGroups += SubsAppGroupConfig(
                            subsId = XAdSkipRulePack.UNIFIED_ID, appId = appId, groupKey = target.second,
                            enable = isEnabled,
                            exclude = exclusions.flatMap { it.lines() }.map { it.trim() }
                                .filter { it.isNotEmpty() }.distinct().sorted().joinToString("\n"),
                        )
                    } else {
                        val parsed = exclusions.map(ExcludeData::parse)
                        val apps = mutableMapOf<String, Boolean>()
                        parsed.forEach { data ->
                            data.appIds.forEach { (id, excluded) -> apps[id] = apps[id] == true || excluded }
                        }
                        targetGlobalGroups += SubsGlobalGroupConfig(
                            subsId = XAdSkipRulePack.UNIFIED_ID, groupKey = target.second,
                            enable = isEnabled,
                            exclude = ExcludeData(apps, parsed.flatMap { it.activityIds }.toSet()).stringify(),
                        )
                    }
                }
            }
        // A source the user never installed must not introduce a new active
        // global fallback that could bypass their existing exclusions.
        val appStates = targetAppGroups.associateBy { it.appId to it.groupKey }.toMutableMap()
        val globalStates = targetGlobalGroups.associateBy { it.groupKey }.toMutableMap()
        mappings.groupBy { it.appId to it.unifiedGroupKey }.forEach { (target, sources) ->
            if (sources.none { it.conflictDisabled }) return@forEach
            val conflicts = sources.flatMap { it.conflictsWith }.toSet()
            val appId = target.first
            val blocked = conflicts.isEmpty() || conflicts.any { key ->
                if (appId == null) globalStates[key]?.enable == true else appStates[appId to key]?.enable == true
            }
            if (blocked) {
                if (appId == null) {
                    globalStates[target.second]?.let { globalStates[target.second] = it.copy(enable = false) }
                } else {
                    val appTarget = appId to target.second
                    appStates[appTarget]?.let { appStates[appTarget] = it.copy(enable = false) }
                }
            }
        }
        return Plan(
            previousConfigs = configs,
            item = SubsItem(
                id = XAdSkipRulePack.UNIFIED_ID,
                order = items.values.minOf { it.order },
                enable = items.values.any { it.enable },
                enableUpdate = true,
            ),
            archivedItems = items.values.map { it.copy(enable = false, enableUpdate = false) },
            appConfigs = appConfigs.groupBy { it.appId }.map { (appId, values) ->
                SubsAppConfig(values.all { it.enable }, XAdSkipRulePack.UNIFIED_ID, appId)
            },
            appGroupConfigs = appStates.values.toList(),
            globalGroupConfigs = globalStates.values.toList(),
        )
    }
}
