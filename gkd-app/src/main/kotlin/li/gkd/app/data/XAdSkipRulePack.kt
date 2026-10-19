package li.gkd.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.gkd.app.app
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.db.Db
import li.gkd.db.SubsItem

// XAdSkip additions, 2026-09-13. SPDX-License-Identifier: GPL-3.0-only
object XAdSkipRulePack {
    enum class Community(
        val title: String,
        val assetPath: String,
        val upstreamId: Long,
        val subscriptionId: Long,
    ) {
        GANLIN("甘霖", "xadskip/community/ganlinte/ganlin_gkd.json5", 233L, 2026091302L),
        AOGUAI("奥怪", "xadskip/community/aoguai/aoguai_gkd.json5", 86L, 2026091303L),
    }

    private val communityImportMutex = Mutex()

    data class PreparedImport(
        val subscription: RawSubscription,
        val item: SubsItem,
    )

    /** Only a new installation receives defaults; existing choices always take priority. */
    fun prepareFirstRunImport(
        initialized: Boolean,
        existingItems: List<SubsItem>,
        readAsset: (String) -> String,
    ): PreparedImport? {
        if (initialized || existingItems.isNotEmpty()) return null
        // Of the bundled snapshots, Ganlin covers the most apps and rule groups.
        val pack = Community.GANLIN
        val subscription = prepareCommunity(readAsset(pack.assetPath), pack)
        return PreparedImport(
            subscription = subscription,
            item = SubsItem(
                id = subscription.id,
                order = 0,
                enable = true,
                enableUpdate = false,
            ),
        )
    }

    fun prepareCommunity(source: String, pack: Community): RawSubscription {
        val subscription = RawSubscription.parse(source)
        require(subscription.id == pack.upstreamId) { "${pack.title}规则包标识不一致" }
        // A bundled copy has its own persistent identity and cannot update from upstream.
        // Keep every original rule, category, default state and author attribution.
        return subscription.copy(
            id = pack.subscriptionId,
            name = "${subscription.name}（内置）",
            updateUrl = null,
            checkUpdateUrl = null,
        )
    }

    suspend fun importCommunity(pack: Community): Boolean = withContext(Dispatchers.IO) {
        communityImportMutex.withLock {
            val items = Db.subsItemDao.queryAll()
            if (items.any { it.id == pack.subscriptionId }) return@withLock false
            val source = app.assets.open(pack.assetPath).bufferedReader().use { it.readText() }
            val subscription = prepareCommunity(source, pack)
            SubscriptionRepository.saveWithItem(
                subscription,
                SubsItem(
                    id = subscription.id,
                    order = (items.maxOfOrNull { it.order } ?: -1) + 1,
                    enable = false,
                    enableUpdate = false,
                ),
            )
            true
        }
    }

    suspend fun importDrafts(): Boolean = withContext(Dispatchers.IO) {
        val id = XAdSkipConfig.draftSubscriptionId
        // Import is explicit and idempotent: never overwrite a teammate's reviewed edits.
        if (Db.subsItemDao.queryAll().any { it.id == id }) return@withContext false
        val source = app.assets.open("xadskip/ltt-drafts.json").bufferedReader().use { it.readText() }
        val subscription = RawSubscription.parse(source)
        require(subscription.id == id) { "迁移规则包标识不一致" }
        require(subscription.globalGroups.isEmpty()) { "迁移草稿不应包含全局点击规则" }
        subscription.apps.forEach { target ->
            target.groups.forEach { group ->
                require(group.enable == false) { "待验证规则应默认关闭" }
                group.errorDesc?.let { error("${target.id}: $it") }
            }
        }
        SubscriptionRepository.saveWithItem(
            subscription,
            SubsItem(id = id, order = 0, enable = false, enableUpdate = false),
        )
        true
    }
}
