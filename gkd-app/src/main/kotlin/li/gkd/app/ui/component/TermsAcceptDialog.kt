package li.gkd.app.ui.component

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import li.gkd.app.MainActivity
import li.gkd.app.META
import li.gkd.app.data.XAdSkipConfig
import li.gkd.app.feature.settings.XAdSkipPrivacyDialog
import li.gkd.app.ui.share.LocalMainViewModel
import li.gkd.app.util.throttle

@Composable
fun TermsAcceptDialog() {
    val mainVm = LocalMainViewModel.current
    val context = LocalActivity.current as MainActivity
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        ),
    )
    AppAlertDialog(
        onDismissRequest = {},
        title = { Text(text = "欢迎使用 ${META.appName}") },
        text = {
            Column {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = buildAnnotatedString {
                        append("开启后，本应用会通过无障碍服务读取屏幕内容，并按您启用的规则自动点击。您可随时在首页暂停。\n\n")
                        append("点击“同意并前往开启”后，将打开系统授权页面，请您亲自确认开启。规则已随应用提供，无需手动导入。\n\n")
                        append("继续前，请阅读")
                        withLink(LinkAnnotation.Url(XAdSkipConfig.useNotesUrl, linkStyles)) {
                            append("使用说明")
                        }
                        append("和下方隐私说明。")
                    },
                )
                TextButton(onClick = { showPrivacy = true }) {
                    Text("查看隐私说明")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = throttle { mainVm.acceptTermsAndOpenA11ySetup() }) {
                Text(text = "同意并前往开启")
            }
        },
        dismissButton = {
            TextButton(onClick = throttle { context.finish() }) {
                Text(text = "不同意并退出")
            }
        },
    )
    XAdSkipPrivacyDialog(
        visible = showPrivacy,
        onDismissRequest = { showPrivacy = false },
    )
}
