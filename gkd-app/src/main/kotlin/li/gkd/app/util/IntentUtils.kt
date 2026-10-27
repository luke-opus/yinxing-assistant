package li.gkd.app.util

import li.gkd.app.util.ToastUtils.toast

import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.net.toUri
import li.gkd.app.META
import li.gkd.app.app
import li.gkd.app.permission.PermissionStates
import li.gkd.app.platform.lifecycle.MainActivityVisibility
import li.gkd.app.service.A11yService
import li.songe.codeorigin.CallSite
import kotlin.reflect.KClass

object IntentUtils {
    // Some versions/OEMs allow this action; others protect the activity with a system permission.
    private const val ACCESSIBILITY_DETAILS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val SETTINGS_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    fun openWeChatScaner() {
        val intent = app.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.apply {
            putExtra("LauncherUI.From.Scaner.Shortcut", true)
        }
        if (intent == null) {
            toast("请检查微信是否安装或禁用")
            return
        }
        app.tryStartActivity(intent)
    }

    fun a11ySettingsIntents(): List<Intent> = buildList {
        if (AndroidTarget.Q) {
            add(Intent(ACCESSIBILITY_DETAILS_ACTION).apply {
                // Settings reads this extra as a flattened String, not a Parcelable ComponentName.
                putExtra(Intent.EXTRA_COMPONENT_NAME, A11yService.a11yCn.flattenToString())
            })
        }
        add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // Best-effort highlighting in Settings implementations that honor this hint.
            // This only selects a visible preference; it does not choose a protected fragment.
            val component = A11yService.a11yCn.flattenToString()
            putExtra(SETTINGS_FRAGMENT_ARGS_KEY, component)
            putExtra(SETTINGS_SHOW_FRAGMENT_ARGS, Bundle().apply {
                putString(SETTINGS_FRAGMENT_ARGS_KEY, component)
            })
        })
    }

    fun appDetailsSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = "package:${app.packageName}".toUri()
    }

    fun openA11ySettings() {
        for (intent in a11ySettingsIntents()) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                return
            } catch (e: SecurityException) {
                LogUtils.d("openA11yDetailsSettings", e)
            } catch (e: ActivityNotFoundException) {
                LogUtils.d("openA11yDetailsSettings", e)
            }
        }
        toast("无法打开无障碍设置，请在系统设置中打开")
    }

    fun openAppDetailsSettings() {
        val intent = appDetailsSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.tryStartActivity(intent)
    }

    fun openUri(uri: String) {
        val parsedUri = try {
            uri.toUri()
        } catch (e: Exception) {
            e.printStackTrace()
            toast("非法链接")
            return
        }
        openUri(parsedUri)
    }

    fun openUri(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.tryStartActivity(intent)
    }

    fun <T : Service> stopServiceByClass(clazz: KClass<T>) {
        val intent = Intent(app, clazz.java)
        app.stopService(intent)
    }

    fun <T : Service> startForegroundServiceByClass(
        clazz: KClass<T>,
        @CallSite loc: String = "",
    ) {
        if (!PermissionStates.notification.checkOrToast(loc = loc)) return
        if (!PermissionStates.foregroundServiceSpecialUse.checkOrToast(loc = loc)) return
        val intent = Intent(app, clazz.java)
        try {
            app.startForegroundService(intent)
        } catch (e: Throwable) {
            LogUtils.d(e, loc = loc)
            val prefix = if (MainActivityVisibility.isVisible) "" else "${META.appName}: "
            toast("${prefix}启动服务失败: ${e.message}", forced = true, loc = loc)
        }
    }
}
