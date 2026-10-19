package li.gkd.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import li.gkd.app.ui.component.AppAlertDialog

/** Bundled app-specific notice, also available before accessibility is enabled. */
@Composable
fun XAdSkipPrivacyDialog(visible: Boolean, onDismissRequest: () -> Unit) {
    if (visible) {
        AppAlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text("XAdSkip 应用隐私说明") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("本说明适用于此 APK，与网站的网页隐私说明分开。更新日期：2026-10-19。")
                    PrivacySection(
                        "界面读取与自动操作",
                        "开启无障碍服务后，应用可读取当前界面的文字、控件标识、位置、应用和页面信息，并按已启用的规则执行点击等操作。应用会读取已安装应用的包名、名称、版本等信息，用于显示应用列表和匹配规则。规则匹配在设备上执行；规则也可能包含广告以外的自动化操作，请按需关闭。",
                    )
                    PrivacySection(
                        "设备上的记录",
                        "应用在设备上保存设置、订阅、应用访问与规则触发记录；本地运行和崩溃日志可能包含设备型号、系统和应用版本、页面信息、错误消息与调用堆栈。启用事件记录时，日志还可能包含界面文字和描述。使用快照或开启相应触发功能后，会保存界面节点及截图，其中可能有聊天、账号等个人信息。",
                    )
                    PrivacySection(
                        "联网功能",
                        "内置甘霖规则从 APK 离线导入，不自动连接上游更新。你添加远程订阅后，应用会连接该地址下载规则，并可能按订阅更新设置再次连接；打开在线说明、规则图片或外部链接也会访问对应服务。服务提供方会接收完成请求所需的网络信息，例如 IP 地址。高级功能中的 HTTP 调试服务需要主动开启，会向能够连接该设备的客户端提供调试接口；不用时请关闭。",
                    )
                    PrivacySection(
                        "导出、分享与反馈",
                        "本版本不提供内置的日志或快照云上传功能。导出日志可能包含数据库、设置、订阅、运行与崩溃日志、应用列表和权限状态；快照文件可能含截图及界面节点。选择系统分享或邮件后，文件会交给你选择的应用或接收方。请先检查文件，删除或遮盖敏感内容，不要发送密码、验证码或无关身份证件。",
                    )
                    PrivacySection(
                        "关闭与删除",
                        "可以关闭单个订阅或规则、暂停自动操作，或在安卓系统设置中关闭 XAdSkip 无障碍权限。日志和快照可在相应记录页面删除；系统应用管理中的“清除存储”会重置本地应用数据。已保存到下载目录、已分享或已发送的副本需另行处理。",
                    )
                    PrivacySection(
                        "项目与联系",
                        "XAdSkip 是基于 GKD 的独立开源项目，不代表 GKD 或李跳跳原作者。应用问题、隐私咨询与删除请求请联系 1345134186@qq.com；发送邮件时，收件方会收到你的邮箱及主动提供的内容。",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissRequest) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body)
    }
}
