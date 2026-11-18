package li.gkd.app.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import li.gkd.app.data.RawSubscription
import li.gkd.app.app
import li.gkd.app.data.XAdSkipConfig
import li.gkd.app.data.XAdSkipRuleUpdater
import li.gkd.app.data.XAdSkipRulePack
import li.gkd.db.SubsItem
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.store.AppStore
import li.gkd.app.util.MutexState
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.core.state.Loadable
import li.gkd.app.data.subscription.SubscriptionResult
import li.gkd.app.data.subscription.SubscriptionSnapshot
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.share.message
import li.gkd.app.util.ToastUtils.toast
import li.gkd.db.Db
import li.gkd.db.LOCAL_SUBS_ID

data class SubsManageUiState(
    val subItems: List<SubsItem>,
    val subscriptions: Map<Long, RawSubscription>,
    val refreshing: Boolean,
    val legacyArchiveIds: Set<Long>,
    val loadErrors: Map<Long, Exception>,
    val refreshErrors: Map<Long, Exception>,
)

private fun buildSubsManageUiState(
    subItems: List<SubsItem>,
    snapshot: SubscriptionSnapshot,
    refreshing: Boolean,
    legacyArchiveIds: Set<Long>,
) = SubsManageUiState(
    subItems = subItems,
    subscriptions = snapshot.subscriptions,
    refreshing = refreshing,
    legacyArchiveIds = legacyArchiveIds,
    loadErrors = snapshot.loadErrors,
    refreshErrors = snapshot.updateErrors,
)

class SubsManageVm : BaseViewModel() {
    private val refreshMutex = MutexState()
    val manualRefreshingFlow: StateFlow<Boolean> get() = refreshMutex.state
    val refreshMessageFlow: StateFlow<String?>
        field = MutableStateFlow(null)

    private val batchMutex = MutexState()
    val batchBusyFlow: StateFlow<Boolean> get() = batchMutex.state

    suspend fun runBatchAction(action: suspend () -> Unit) {
        batchMutex.tryWithStateLock(action)
    }

    val settingsDialogVisibleFlow: StateFlow<Boolean>
        field = MutableStateFlow(false)
    val powerWarningItemFlow: StateFlow<SubsItem?>
        field = MutableStateFlow(null)

    val uiState: StateFlow<Loadable<SubsManageUiState>> =
        SubscriptionRepository.snapshotFlow.map { snapshotState ->
            val archiveIds = if (snapshotState is Loadable.Ready) withContext(Dispatchers.IO) {
                snapshotState.value.subscriptions.values.mapNotNull { subscription ->
                    if (subscription.id != XAdSkipConfig.draftSubscriptionId &&
                        XAdSkipRulePack.Community.entries.none { it.subscriptionId == subscription.id }) return@mapNotNull null
                    val baseline = runCatching {
                        XAdSkipRulePack.legacyBaseline(subscription) { path ->
                            app.assets.open(path).bufferedReader().use { it.readText() }
                        }
                    }.getOrNull()
                    subscription.id.takeIf { baseline != null && XAdSkipRulePack.matchesPackagedContent(subscription, baseline) }
                }.toSet()
            } else emptySet()
            snapshotState to archiveIds
        }.flatMapLatest { (snapshotState, archiveIds) ->
            when (snapshotState) {
                Loadable.Loading -> flowOf(Loadable.Loading)
                is Loadable.Failure -> flowOf(snapshotState)
                is Loadable.Ready -> combine(
                    Db.subsItemDao.query(),
                    SubscriptionRepository.updating,
                ) { subItems, refreshing ->
                    buildSubsManageUiState(
                        subItems = subItems,
                        snapshot = snapshotState.value,
                        refreshing = refreshing,
                        legacyArchiveIds = archiveIds,
                    )
                }.map<SubsManageUiState, Loadable<SubsManageUiState>> { Loadable.Ready(it) }
                    .catch { emit(Loadable.Failure(it)) }
            }
        }.stateIn(scope, SharingStarted.Eagerly, Loadable.Loading)

    fun setUpdateInterval(value: Long) {
        AppStore.updateSettings { it.copy(updateSubsInterval = value) }
    }

    fun setPowerWarningEnabled(enabled: Boolean) {
        AppStore.updateSettings { it.copy(subsPowerWarn = enabled) }
    }

    fun setSettingsDialogVisible(visible: Boolean) {
        settingsDialogVisibleFlow.value = visible
    }

    fun toggleMatching() {
        AppStore.updateSettings { it.copy(enableMatch = !it.enableMatch) }
    }

    fun refresh() {
        scope.launchUi {
            refreshMutex.tryWithStateLock {
                refreshMessageFlow.value = null
                val items = Db.subsItemDao.queryAll()
                val unified = items.find { it.id == XAdSkipRulePack.UNIFIED_ID }
                val messages = mutableListOf<String>()
                if (unified != null) {
                    if (!unified.enableUpdate) {
                        messages += "统一库的自动更新已关闭，本次保留当前规则"
                    } else {
                        val before = XAdSkipRuleUpdater.statusFlow.value
                        XAdSkipRuleUpdater.checkOnAppOpen(force = true)
                        val result = if (XAdSkipRuleUpdater.statusFlow.value.checking) {
                            XAdSkipRuleUpdater.statusFlow.first { !it.checking }
                        } else XAdSkipRuleUpdater.statusFlow.value
                        messages += if (result === before && !before.checking) {
                            "本次未完成新的规则检查，请确认更新开关或稍后重试"
                        } else result.message ?: "本次规则检查未返回结果，当前规则保持不变"
                    }
                }
                if (items.any { it.id != XAdSkipRulePack.UNIFIED_ID && it.enableUpdate && it.updateUrl != null }) {
                    SubscriptionRepository.refresh().message?.let { messages += "自定义订阅：$it" }
                }
                val message = messages.joinToString("；").ifEmpty { "当前没有可在线更新的规则" }
                refreshMessageFlow.value = message
                toast(message)
            }
        }
    }

    suspend fun deleteSubscriptions(ids: Set<Long>): SubscriptionResult =
        SubscriptionRepository.delete(*(ids - LOCAL_SUBS_ID).toLongArray())

    fun updateOrder(items: List<SubsItem>) {
        scope.launchUi {
            val current = Db.subsItemDao.queryAll()
            val visibleIds = items.map { it.id }.toSet()
            if (visibleIds.size != items.size || !current.map { it.id }.containsAll(visibleIds)) {
                toast("规则列表已改变，请重新排序")
                return@launchUi
            }
            val byId = current.associateBy { it.id }
            val ordered = items.iterator()
            // Reorder only occupied visible slots; hidden archives keep their place.
            val next = current.map { item ->
                if (item.id in visibleIds) byId.getValue(ordered.next().id) else item
            }.mapIndexedNotNull { index, item -> item.copy(order = index).takeIf { it.order != item.order } }
            if (next.isNotEmpty()) Db.subsItemDao.batchUpdateOrder(next)
        }
    }

    private fun shouldWarnBeforeEnabling(item: SubsItem): Boolean {
        val state = uiState.value.value ?: return false
        return storeFlow.value.subsPowerWarn &&
            !item.isLocal &&
            state.subItems.any { current ->
                current.id != item.id &&
                    current.enable &&
                    !current.isLocal &&
                    state.subscriptions[current.id]?.hasRule != false
            }
    }

    fun requestSubscriptionEnabled(item: SubsItem, enabled: Boolean) {
        if (enabled && shouldWarnBeforeEnabling(item)) {
            powerWarningItemFlow.value = item
        } else {
            setSubscriptionEnabled(item, enabled)
        }
    }

    fun dismissPowerWarning() {
        powerWarningItemFlow.value = null
    }

    fun confirmPowerWarning() {
        val item = powerWarningItemFlow.value ?: return
        powerWarningItemFlow.value = null
        setSubscriptionEnabled(item, true)
    }

    private fun setSubscriptionEnabled(item: SubsItem, enabled: Boolean) {
        scope.launchUi {
            Db.subsItemDao.updateEnable(item.id, enabled)
        }
    }

    suspend fun addOrModifySubscription(
        url: String,
        oldItem: SubsItem? = null,
    ): SubscriptionResult = SubscriptionRepository.addOrModifyRemote(url, oldItem)
}
