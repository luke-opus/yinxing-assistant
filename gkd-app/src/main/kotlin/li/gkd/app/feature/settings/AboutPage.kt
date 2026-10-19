package li.gkd.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import li.gkd.app.data.XAdSkipConfig
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.META
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.store.AppStore
import li.gkd.app.ui.component.PerfIcon
import li.gkd.app.ui.component.PerfIconButton
import li.gkd.app.ui.component.PerfTopAppBar
import li.gkd.app.ui.component.RotatingLoadingIcon
import li.gkd.app.ui.component.SettingItem
import li.gkd.app.ui.component.TextMenu
import li.gkd.app.ui.share.LocalMainViewModel
import li.gkd.app.ui.style.EmptyHeight
import li.gkd.app.ui.style.itemPadding
import li.gkd.app.ui.style.titleItemPadding
import li.gkd.app.util.UpdateChannelOption
import li.gkd.app.util.findOption
import li.gkd.app.ui.share.launchUiAction
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.throttle

@Serializable
data object AboutRoute : NavKey

@Composable
fun AboutPage() {
    val mainVm = LocalMainViewModel.current
    var showVersionInfoDialog by rememberSaveable { mutableStateOf(false) }
    var showShareAppDialog by rememberSaveable { mutableStateOf(false) }
    var showPrivacyDialog by rememberSaveable { mutableStateOf(false) }
    val store by storeFlow.collectAsStateWithLifecycle()
    val updateChannel = UpdateChannelOption.objects.findOption(store.updateChannel)

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
                    Text(
                        text = "X",
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
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

            SettingItem(
                imageVector = null,
                title = "XAdSkip 网站",
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
            if (mainVm.updateStatus != null) {
                Text(
                    text = "更新",
                    modifier = Modifier.titleItemPadding(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextMenu(
                    title = "更新渠道",
                    option = updateChannel
                ) {
                    if (mainVm.updateStatus.checkUpdatingFlow.value) return@TextMenu
                    if (it.value == UpdateChannelOption.Beta.value) {
                        mainVm.scope.launchUi {
                            if (!mainVm.dialogRequests.confirm(
                                title = "版本渠道",
                                text = "测试版本渠道更新快\n但不稳定可能存在较多BUG\n请谨慎使用",
                            )) return@launchUi
                            AppStore.updateSettings { settings ->
                                settings.copy(updateChannel = it.value)
                            }
                        }
                    } else {
                        AppStore.updateSettings { settings ->
                            settings.copy(updateChannel = it.value)
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .clickable(
                            onClick = throttle {
                                mainVm.updateStatus.checkUpdate(true)
                            }
                        )
                        .fillMaxWidth()
                        .itemPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "检查更新",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    RotatingLoadingIcon(loading = mainVm.updateStatus.checkUpdatingFlow.collectAsStateWithLifecycle().value)
                }
            }
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
    Column(
        modifier = Modifier
            .clickable(onClick = throttle(mainVm.scope.launchUiAction {
                val noticeText = "请描述 XAdSkip 的问题、手机型号和系统版本；规则问题请注明应用及规则名称。\n\n" +
                    "日志和快照可能含界面文字、账号或聊天信息，请先检查并删除敏感内容。不要发送密码、验证码或无关身份证件。\n\n" +
                    "继续后可打开邮件应用联系 1345134186@qq.com。"
                if (!mainVm.dialogRequests.confirm(
                    title = "反馈须知",
                    text = noticeText,
                    confirmText = "继续",
                    dismissOnRequest = true,
                )) return@launchUiAction
                mainVm.openUrl(XAdSkipConfig.feedbackUrl)
            }))
            .fillMaxWidth()
            .itemPadding()
    ) {
        Text(
            text = "问题反馈",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

