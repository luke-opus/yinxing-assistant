package li.gkd.app.feature.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.META
import li.gkd.app.core.state.Loadable
import li.gkd.app.data.XAdSkipConfig
import li.gkd.app.data.XAdSkipRulePack
import li.gkd.app.data.appinfo.AppInfoRepository
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.app.ui.AppConfigRoute
import li.gkd.app.ui.component.PerfIcon
import li.gkd.app.ui.component.PerfIconButton
import li.gkd.app.ui.component.PerfTopAppBar
import li.gkd.app.ui.share.LocalMainViewModel
import li.gkd.app.util.IntentUtils

@Serializable
data object RuleCoverageRoute : NavKey

private data class CoverageApp(val id: String, val name: String, val groupCount: Int, val advertisementGroupCount: Int, val installed: Boolean)

private fun isAdvertisementGroup(name: String) =
    name.startsWith("开屏广告") || name.startsWith("局部广告") ||
        name.startsWith("全屏广告") || name.startsWith("分段广告")

@Composable
fun RuleCoveragePage() {
    val mainVm = LocalMainViewModel.current
    val snapshot by SubscriptionRepository.snapshotFlow.collectAsStateWithLifecycle()
    val installed by AppInfoRepository.appInfoMapFlow.collectAsStateWithLifecycle()
    val summary by SubscriptionState.ruleSummaryFlow.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var onlyInstalled by rememberSaveable { mutableStateOf(false) }
    val apps = remember(snapshot, installed) {
        val subscriptions = snapshot.value?.subscriptions.orEmpty()
        val unified = subscriptions[XAdSkipRulePack.UNIFIED_ID]
        val configured = (if (unified != null) listOf(unified) else subscriptions.values.toList())
            .flatMap { it.apps }.groupBy { it.id }
        (configured.keys + installed.keys).map { id ->
            CoverageApp(
                id = id,
                name = installed[id]?.name ?: configured[id]?.firstNotNullOfOrNull { it.name } ?: id,
                groupCount = configured[id].orEmpty().sumOf { it.groups.size },
                advertisementGroupCount = configured[id].orEmpty().sumOf { app -> app.groups.count { isAdvertisementGroup(it.name) } },
                installed = installed[id]?.hidden == false,
            )
        }.sortedWith(compareBy<CoverageApp> { it.name.lowercase() }.thenBy { it.id })
    }
    val visible = remember(apps, query, onlyInstalled) {
        apps.filter { app ->
            (if (onlyInstalled) app.installed else app.groupCount > 0) &&
                (query.isBlank() || app.name.contains(query.trim(), true) || app.id.contains(query.trim(), true))
        }
    }
    Scaffold(topBar = {
        PerfTopAppBar(title = { Text("支持哪些应用") }, navigationIcon = {
            PerfIconButton(imageVector = PerfIcon.ArrowBack, onClick = mainVm::popPage)
        })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("有规则不等于每个广告都能跳过", style = MaterialTheme.typography.titleMedium)
                    Text("规则按应用名称和包名对应。不同应用版本、广告画面会影响效果；没有专用规则的应用也可能命中通用开屏规则，尚未验证的效果不会标为已支持。")
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), label = { Text("搜索应用名称或包名") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !onlyInstalled, onClick = { onlyInstalled = false }, label = { Text("规则库应用") })
                        FilterChip(selected = onlyInstalled, onClick = { onlyInstalled = true }, label = { Text("本机已识别") })
                    }
                    Text(if (onlyInstalled) "只显示系统当前允许识别的应用，不代表手机全部应用。" else "共 ${apps.count { it.advertisementGroupCount > 0 }} 个应用收录广告规则，另有非广告和待验证配置；收录不代表实测通过。", style = MaterialTheme.typography.bodySmall)
                }
            }
            when (snapshot) {
                is Loadable.Loading -> item { Text("正在读取规则…", Modifier.padding(16.dp)) }
                is Loadable.Failure -> item { Text("规则暂时读取失败，请返回首页重试。", Modifier.padding(16.dp)) }
                is Loadable.Ready -> {
                    if (visible.isEmpty()) item {
                        Column(Modifier.padding(16.dp)) {
                            Text("未找到对应应用，可以提交截图帮助补充规则。")
                            TextButton(onClick = { IntentUtils.openUri(XAdSkipConfig.feedbackUrlForVersion(META.versionName, META.versionCode)) }) { Text("反馈问题") }
                        }
                    }
                    items(visible, key = { it.id }) { app ->
                        val enabledGroups = summary.appIdToGroups[app.id]?.count { isAdvertisementGroup(it.name) } ?: 0
                        Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(app.name, style = MaterialTheme.typography.titleMedium)
                                Text(app.id, style = MaterialTheme.typography.bodySmall)
                                Text(if (app.advertisementGroupCount > 0) "${app.advertisementGroupCount} 组广告规则 · 当前启用 $enabledGroups 组" else "暂无专用广告规则 · 通用匹配效果待验证")
                                if (app.groupCount > app.advertisementGroupCount) {
                                    Text("另有 ${app.groupCount - app.advertisementGroupCount} 组其他或待验证规则", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { mainVm.navigatePage(AppConfigRoute(app.id)) }) { Text("查看规则与开关") }
                            }
                        }
                    }
                    item { Text("误点或漏跳时，可到触发记录查看执行过的规则，或提交截图反馈。", Modifier.padding(16.dp)) }
                }
            }
        }
    }
}
