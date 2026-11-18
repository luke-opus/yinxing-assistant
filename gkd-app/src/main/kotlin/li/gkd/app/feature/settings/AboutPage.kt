package li.gkd.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.data.XAdSkipConfig
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.META
import li.gkd.app.R
import li.gkd.app.store.AppStore
import li.gkd.app.ui.component.PerfIcon
import li.gkd.app.ui.component.PerfIconButton
import li.gkd.app.ui.component.PerfTopAppBar
import li.gkd.app.ui.component.SettingItem
import li.gkd.app.ui.component.TextSwitch
import li.gkd.app.ui.share.LocalMainViewModel
import li.gkd.app.ui.style.EmptyHeight
import li.gkd.app.ui.style.titleItemPadding
import li.gkd.app.util.IntentUtils

@Serializable
data object AboutRoute : NavKey

@Composable
fun AboutPage() {
    val mainVm = LocalMainViewModel.current
    var showVersionInfoDialog by rememberSaveable { mutableStateOf(false) }
    var showShareAppDialog by rememberSaveable { mutableStateOf(false) }
    var showPrivacyDialog by rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    PerfIconButton(
                        imageVector = PerfIcon.ArrowBack,
                        onClick = {
                            mainVm.popPage()
                        },
                    )
                },
                title = { Text(text = "关于") },
                actions = {
                    PerfIconButton(
                        imageVector = PerfIcon.Share,
                        onClick = { showShareAppDialog = true },
                    )
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable(onClick = { showVersionInfoDialog = true })
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = META.appName, style = MaterialTheme.typography.titleMedium)
                    Text(text = "独立开源项目 · 基于 GKD · 非李跳跳官方续作", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = META.versionName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            mainVm.updateStatus?.let { updateStatus ->
                val checking by updateStatus.checkUpdatingFlow.collectAsStateWithLifecycle()
                val message by updateStatus.checkMessageFlow.collectAsStateWithLifecycle()
                val settings by AppStore.storeFlow.collectAsStateWithLifecycle()
                SettingItem(
                    title = if (checking) "正在检查更新…" else "检查新版本",
                    subtitle = if (checking) "正在连接${META.appName}官网" else
                        message ?: "当前版本 v${META.versionName}",
                    onClickLabel = "检查${META.appName}新版本",
                    onClick = if (checking) null else ({ updateStatus.checkUpdate(manual = true) }),
                )
                TextSwitch(
                    title = "自动检查新版本",
                    subtitle = "进入应用时检查，间隔至少 15 分钟；下载和安装由你确认",
                    checked = settings.autoCheckAppUpdate,
                    onCheckedChange = updateStatus::setAutoCheck,
                )
            }
            SettingItem(
                imageVector = null,
                title = "${META.appName}网站",
                onClick = { mainVm.openUrl(XAdSkipConfig.homePageUrl) },
            )
            SettingItem(
                imageVector = null,
                title = "使用说明",
                onClick = { mainVm.navigateWebPage(XAdSkipConfig.useNotesUrl) },
            )
            SettingItem(
                imageVector = null,
                title = "应用隐私说明（本地）",
                onClick = { showPrivacyDialog = true },
            )
            SettingItem(
                imageVector = null,
                title = "网页隐私说明",
                onClick = { mainVm.navigateWebPage(XAdSkipConfig.websitePrivacyUrl) },
            )
            SettingItem(
                imageVector = null,
                title = "对应版本源码与开源许可",
                onClick = { mainVm.openUrl(XAdSkipConfig.licensesUrl) },
            )
            SettingItem(
                imageVector = null,
                title = "GKD 上游源码（致谢）",
                onClick = { mainVm.openUrl(XAdSkipConfig.upstreamRepositoryUrl) },
            )

            FeedbackSection()
            SettingItem(
                title = "导出日志",
                imageVector = PerfIcon.Share,
                onClick = {
                    mainVm.shareLog.show()
                }
            )
            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }

    XAdSkipPrivacyDialog(showPrivacyDialog) { showPrivacyDialog = false }

    AboutDialogs(
        showVersionInfo = showVersionInfoDialog,
        onDismissVersionInfo = { showVersionInfoDialog = false },
        showShareApp = showShareAppDialog,
        onDismissShareApp = { showShareAppDialog = false },
    )
}

@Composable
private fun FeedbackSection() {
    val mainVm = LocalMainViewModel.current
    val primaryColor = MaterialTheme.colorScheme.primary
    Text(
        text = "反馈",
        modifier = Modifier.titleItemPadding(),
        style = MaterialTheme.typography.titleSmall,
        color = primaryColor,
    )
    SettingItem(
        title = "问题反馈",
        subtitle = "打开反馈页面，自行选择要提交的信息",
        onClick = {
            IntentUtils.openUri(XAdSkipConfig.feedbackUrlForVersion(META.versionName, META.versionCode))
        },
    )
    SettingItem(
        title = "邮件联系",
        subtitle = "1345134186@qq.com",
        onClick = { mainVm.openUrl(XAdSkipConfig.feedbackEmailUrl) },
    )
}

