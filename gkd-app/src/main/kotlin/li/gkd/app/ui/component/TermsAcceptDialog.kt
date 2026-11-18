package li.gkd.app.ui.component

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
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
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "帮您跳过部分广告，少点几下",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "先开启无障碍辅助，才能自动跳过",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "这项功能需要您亲自授权。只安装应用、导入规则，还不能自动点击。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                A11ySetupStep(
                    number = 1,
                    title = "前往系统授权页",
                    description = "点下方“同意并前往开启”。若显示应用列表，请选择 ${META.appName}。",
                )
                A11ySetupStep(
                    number = 2,
                    title = "打开“使用 ${META.appName}”",
                    description = "阅读系统说明后，亲自打开开关并确认。",
                )
                A11ySetupStep(
                    number = 3,
                    title = "返回，看到“已开启”",
                    description = "规则已备好，无需自己导入。之后可随时在首页暂停。",
                )
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = buildAnnotatedString {
                        append("开启后，${META.appName}会读取屏幕内容，并按已启用规则自动点击。\n\n")
                        append("若提示“受限设置”，请返回首页点“无法开启？”，按提示允许后再继续授权。不同手机的系统页面可能稍有不同。\n\n")
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

@Composable
private fun A11ySetupStep(number: Int, title: String, description: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small schematic phones are illustrations, not interactive permission switches.
        Canvas(modifier = Modifier.size(width = 60.dp, height = 78.dp)) {
            val left = size.width * 0.10f
            val top = size.height * 0.04f
            val width = size.width * 0.80f
            val height = size.height * 0.92f
            drawRoundRect(
                color = colors.surfaceContainerHighest,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            drawRoundRect(
                color = colors.outline,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawLine(
                color = colors.outline,
                start = Offset(size.width * 0.38f, size.height * 0.14f),
                end = Offset(size.width * 0.62f, size.height * 0.14f),
                strokeWidth = 2.dp.toPx(),
            )
            when (number) {
                1 -> {
                    repeat(2) { row ->
                        drawRoundRect(
                            color = colors.outlineVariant,
                            topLeft = Offset(size.width * 0.22f, size.height * (0.30f + row * 0.14f)),
                            size = Size(size.width * 0.56f, size.height * 0.07f),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                    }
                    drawRoundRect(
                        color = colors.primary,
                        topLeft = Offset(size.width * 0.22f, size.height * 0.64f),
                        size = Size(size.width * 0.56f, size.height * 0.14f),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                    )
                }
                2 -> {
                    drawRoundRect(
                        color = colors.primary,
                        topLeft = Offset(size.width * 0.24f, size.height * 0.41f),
                        size = Size(size.width * 0.52f, size.height * 0.20f),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                    drawCircle(
                        color = colors.onPrimary,
                        radius = size.height * 0.067f,
                        center = Offset(size.width * 0.62f, size.height * 0.51f),
                    )
                }
                else -> {
                    drawCircle(
                        color = colors.primary,
                        radius = size.width * 0.24f,
                        center = Offset(size.width * 0.5f, size.height * 0.51f),
                    )
                    drawLine(
                        color = colors.onPrimary,
                        start = Offset(size.width * 0.36f, size.height * 0.50f),
                        end = Offset(size.width * 0.46f, size.height * 0.58f),
                        strokeWidth = 3.dp.toPx(),
                    )
                    drawLine(
                        color = colors.onPrimary,
                        start = Offset(size.width * 0.46f, size.height * 0.58f),
                        end = Offset(size.width * 0.66f, size.height * 0.43f),
                        strokeWidth = 3.dp.toPx(),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$number. $title", style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
