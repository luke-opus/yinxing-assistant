package li.gkd.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.gkd.app.META
import li.gkd.app.R
import li.gkd.app.data.XAdSkipConfig
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.app.permission.PermissionStates
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.service.A11yService
import li.gkd.app.service.ActivityService
import li.gkd.app.service.StatusService
import li.gkd.app.service.topAppIdFlow
import li.gkd.app.store.AppStore.actualA11yScopeAppList
import li.gkd.app.store.AppStore.actionCountFlow
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.feature.log.ActionLogRoute
import li.gkd.app.feature.log.ActivityLogRoute
import li.gkd.app.ui.AppConfigRoute
import li.gkd.app.ui.PrivilegeServiceRoute
import li.gkd.app.ui.WebViewRoute
import li.gkd.app.feature.settings.WorkModeRoute
import li.gkd.app.ui.component.AppAlertDialog
import li.gkd.app.ui.component.GroupNameText
import li.gkd.app.ui.component.PerfIcon
import li.gkd.app.ui.component.PerfSwitch
import li.gkd.app.ui.component.PerfTopAppBar
import li.gkd.app.ui.component.textSize
import li.gkd.app.ui.component.rememberColumnScrollState
import li.gkd.app.ui.share.LocalMainViewModel
import li.gkd.app.ui.style.EmptyHeight
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.app.util.IntentUtils
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.share.statusText
import li.gkd.app.util.throttle
import li.gkd.db.RuleGroupType

@Composable
fun useDashboardPage(): ScaffoldExt {
    val mainVm = LocalMainViewModel.current
    val vm = viewModel<DashboardVm>()
    val ruleSummary by SubscriptionState.ruleSummaryFlow.collectAsStateWithLifecycle()
    val actionCount by actionCountFlow.collectAsStateWithLifecycle()
    val store by storeFlow.collectAsStateWithLifecycle()
    val privilegeContext by privilegeContextFlow.collectAsStateWithLifecycle()
    val automatorMode by mainVm.automatorModeFlow.collectAsStateWithLifecycle()
    val a11yRunning by A11yService.isRunning.collectAsStateWithLifecycle()
    val a11yEnabled by mainVm.a11yServiceEnabledFlow.collectAsStateWithLifecycle()
    val automation by uiAutomationFlow.collectAsStateWithLifecycle()
    val topAppId by topAppIdFlow.collectAsStateWithLifecycle()
    val writeSecureSettings by PermissionStates.writeSecureSettings.stateFlow.collectAsStateWithLifecycle()
    val useA11y = store.useA11y || actualA11yScopeAppList.contains(topAppId)
    val serviceRunning = if (useA11y) a11yRunning else automation != null
    val active = serviceRunning && store.enableMatch
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showRestrictedHelp by rememberSaveable { mutableStateOf(false) }
    val pageScrollState = rememberColumnScrollState()
    val scrollBehavior = pageScrollState.scrollBehavior
    ResetPageScrollOnRequest(BottomNavItem.Dashboard, pageScrollState::resetScrollAndAwait)

    if (showRestrictedHelp) {
        AppAlertDialog(
            title = { Text("无法开启？") },
            text = {
                Text("如果系统提示“受限设置”：\n1. 打开应用信息，点右上角菜单，选择“允许受限设置”。\n2. 返回这里，再点“开启自动跳过”。\n\n只在确认信任本应用后允许；不同手机的名称可能略有不同。")
            },
            onDismissRequest = { showRestrictedHelp = false },
            confirmButton = {
                TextButton(onClick = {
                    showRestrictedHelp = false
                    IntentUtils.openAppDetailsSettings()
                }) { Text("打开应用信息") }
            },
            dismissButton = {
                TextButton(onClick = { showRestrictedHelp = false }) { Text("返回") }
            },
        )
    }

    return ScaffoldExt(
        navItem = BottomNavItem.Dashboard,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PerfTopAppBar(scrollBehavior = scrollBehavior, title = {
                Text(text = stringResource(R.string.app_name))
            })
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(pageScrollState.scrollState)
                .padding(contentPadding)
                .padding(horizontal = itemHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = surfaceCardColors,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = when {
                            active -> "已开启"
                            serviceRunning -> "已暂停"
                            useA11y && a11yEnabled -> "需要重新开启"
                            else -> "尚未开启"
                        },
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = when {
                            active -> "现在可以正常使用手机，遇到规则支持的广告会自动跳过。"
                            serviceRunning -> "已暂停自动点击，点下方按钮即可继续。"
                            useA11y && a11yEnabled -> "服务暂时没有运行，请在系统设置中关闭后重新打开。"
                            else -> "点下方按钮，在系统页面找到“${META.appName}”并开启，再返回这里。"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        onClick = throttle {
                            when {
                                active -> vm.pauseMatching()
                                serviceRunning -> vm.resumeMatching()
                                useA11y && !writeSecureSettings -> {
                                    vm.resumeMatching()
                                    IntentUtils.openA11ySettings()
                                }
                                !useA11y && privilegeContext == null ->
                                    mainVm.navigatePage(PrivilegeServiceRoute)
                                else -> vm.startService()
                            }
                        },
                    ) {
                        Text(
                            text = when {
                                active -> "暂停自动跳过"
                                serviceRunning -> "继续自动跳过"
                                else -> "开启自动跳过"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (!serviceRunning) {
                        Text(
                            text = "开启后会按规则读取屏幕内容并自动点击。系统授权需要您亲自确认。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showRestrictedHelp = true }) {
                            Text("无法开启？", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Text(
                text = "累计自动操作 $actionCount 次",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                onClick = { showMore = !showMore },
            ) {
                Text(
                    text = if (showMore) "收起更多设置" else "更多设置",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (showMore) {
                if (PermissionStates.appOpsRestrictedFlow.collectAsStateWithLifecycle().value) {
                    PageItemCard(
                        imageVector = PerfIcon.WarningAmber,
                        title = "系统限制了权限",
                        subtitle = "查看开启方法",
                        onClickLabel = "查看受限设置说明",
                        onClick = { showRestrictedHelp = true },
                    )
                }
                ServiceStatusCard(
                    subtitle = if (serviceRunning) "服务已连接" else "服务未连接",
                    checked = serviceRunning,
                    onCheckedChange = { newEnabled ->
                        if (newEnabled && useA11y && !writeSecureSettings) {
                            IntentUtils.openA11ySettings()
                        } else if (newEnabled && !useA11y && privilegeContext == null) {
                            mainVm.navigatePage(PrivilegeServiceRoute)
                        } else {
                            vm.switchService()
                        }
                    },
                    mode = automatorMode.label,
                    onModeClick = { mainVm.navigatePage(WorkModeRoute) },
                )
                val manageRunning by StatusService.isRunning.collectAsStateWithLifecycle()
                PageSwitchItemCard(
                    imageVector = PerfIcon.Notifications,
                    title = "常驻通知",
                    subtitle = "在通知栏显示运行状态",
                    checked = manageRunning && store.enableStatusService,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            vm.scope.launchUi {
                                if (mainVm.permissionRequests.ensurePermissions(
                                        PermissionStates.foregroundServiceSpecialUse,
                                        PermissionStates.notification,
                                    )
                                ) {
                                    vm.startStatusService()
                                }
                            }
                        } else {
                            vm.stopStatusService()
                        }
                    },
                )
                val latestRecord by SubscriptionState.latestRecordFlow.collectAsStateWithLifecycle()
                val latestRecordDesc by SubscriptionState.latestRecordDescFlow.collectAsStateWithLifecycle()
                TriggerOverviewCard(
                    subsStatus = ruleSummary.statusText(actionCount),
                    latestRecordDesc = latestRecordDesc,
                    latestRecordIsGlobal = latestRecord?.groupType == RuleGroupType.Global,
                    onOpenActionLog = { mainVm.navigatePage(ActionLogRoute()) },
                    onOpenLatestRecord = {
                        latestRecord?.let {
                            mainVm.navigatePage(AppConfigRoute(appId = it.appId, focusLog = it))
                        }
                    },
                )
                PageItemCard(
                    imageVector = PerfIcon.FormatListBulleted,
                    title = "广告规则",
                    subtitle = "查看和更新订阅",
                    onClickLabel = "打开订阅页面",
                    onClick = { mainVm.handleClickTab(BottomNavItem.SubsManage) },
                )
                PageItemCard(
                    imageVector = PerfIcon.Apps,
                    title = "应用管理",
                    subtitle = "选择哪些应用使用规则",
                    onClickLabel = "打开应用页面",
                    onClick = { mainVm.handleClickTab(BottomNavItem.AppList) },
                )
                PageItemCard(
                    imageVector = PerfIcon.Settings,
                    title = "全部设置",
                    subtitle = "显示、通知和其他选项",
                    onClickLabel = "打开设置页面",
                    onClick = { mainVm.handleClickTab(BottomNavItem.Settings) },
                )
                if (ActivityService.isRunning.collectAsStateWithLifecycle().value) {
                    PageItemCard(
                        title = "界面日志",
                        subtitle = "记录打开的应用及界面",
                        imageVector = PerfIcon.Layers,
                        onClickLabel = "打开界面日志页面",
                        onClick = { mainVm.navigatePage(ActivityLogRoute) },
                    )
                }
                PageItemCard(
                    title = "高级服务",
                    subtitle = "为有需要的用户保留特权服务设置",
                    imageVector = PerfIcon.RocketLaunch,
                    onClickLabel = "打开特权服务页面",
                    onClick = { mainVm.navigatePage(PrivilegeServiceRoute) },
                )
                PageItemCard(
                    title = "规则说明与帮助",
                    subtitle = "查看 XAdSkip 使用说明和常见问题",
                    imageVector = PerfIcon.HelpOutline,
                    onClickLabel = "打开规则文档页面",
                    onClick = { mainVm.navigatePage(WebViewRoute(initUrl = XAdSkipConfig.homePageUrl)) },
                )
            }
            Spacer(modifier = Modifier.height(EmptyHeight))
        }
    }
}


@Composable
private fun PageItemCard(
    imageVector: ImageVector,
    title: String,
    subtitle: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.onClick(label = onClickLabel, action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle(fn = onClick)
    ) {
        IconTextCard(
            imageVector = imageVector,
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageSwitchItemCard(
    imageVector: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val onClick = throttle { onCheckedChange(!checked) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.onClick(label = "切换$title", action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = onClick,
    ) {
        IconTextCard(
            imageVector = imageVector,
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            PerfSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun ServiceStatusCard(
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    mode: String,
    onModeClick: () -> Unit,
) {
    val onStatusClick = throttle { onCheckedChange(!checked) }
    val onModeRowClick = throttle(onModeClick)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
    ) {
        IconTextCard(
            imageVector = PerfIcon.Memory,
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .clickable(
                    onClickLabel = "切换服务状态",
                    onClick = onStatusClick,
                ),
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "服务状态",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            PerfSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(
                start = itemVerticalPadding + 40.dp + itemHorizontalPadding,
                end = itemVerticalPadding,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .clickable(
                    onClickLabel = "前往工作模式页面",
                    onClick = onModeRowClick,
                )
                .padding(
                    start = itemVerticalPadding,
                    end = itemVerticalPadding,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PerfIcon(
                imageVector = PerfIcon.AutoMode,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(itemHorizontalPadding))
            Text(
                text = "工作模式",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = mode,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PerfIcon(
                imageVector = PerfIcon.KeyboardArrowRight,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun IconTextCard(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(itemVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PerfIcon(
            imageVector = imageVector,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(8.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(itemHorizontalPadding))
        content()
    }
}

@Composable
private fun TriggerOverviewCard(
    subsStatus: String,
    latestRecordDesc: String?,
    latestRecordIsGlobal: Boolean,
    onOpenActionLog: () -> Unit,
    onOpenLatestRecord: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = surfaceCardColors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .clickable(
                    onClickLabel = "打开触发记录页面",
                    onClick = throttle(onOpenActionLog),
                )
                .padding(
                    start = itemVerticalPadding,
                    end = itemVerticalPadding,
                    top = itemVerticalPadding,
                    bottom = itemVerticalPadding / 2
                ), verticalAlignment = Alignment.CenterVertically
        ) {
            PerfIcon(
                imageVector = PerfIcon.Equalizer,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(8.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(itemHorizontalPadding))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "触发记录",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "规则误触可定位关闭",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PerfIcon(
                imageVector = PerfIcon.KeyboardArrowRight,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemVerticalPadding)
        ) {
            AnimatedVisibility(subsStatus.isNotEmpty()) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    text = subsStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (latestRecordDesc != null) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable(
                            onClickLabel = "前往应用的规则汇总页面",
                            onClick = throttle(onOpenLatestRecord),
                        )
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        GroupNameText(
                            modifier = Modifier.fillMaxWidth(),
                            preText = "最近触发: ",
                            isGlobal = latestRecordIsGlobal,
                            text = latestRecordDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    PerfIcon(
                        imageVector = PerfIcon.KeyboardArrowRight,
                        modifier = Modifier.textSize(style = MaterialTheme.typography.bodyMedium),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(itemVerticalPadding))
        }
    }
}
