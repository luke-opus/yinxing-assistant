package li.gkd.app.util

import li.gkd.app.util.ToastUtils.toast

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.ktor.client.call.body
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import li.gkd.app.META
import li.gkd.app.app
import li.gkd.app.data.XAdSkipConfig
import li.gkd.app.store.AppStore
import li.gkd.app.store.FileStateStore
import li.gkd.app.ui.component.AppAlertDialog
import li.songe.codeorigin.CallSite
import java.io.File


@Serializable
data class NewVersion(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256: String,
    val versionLogs: List<VersionLog> = emptyList(),
)

@Serializable
data class VersionLog(
    val name: String,
    val code: Int,
    val desc: String,
)

class UpdateStatus(val scope: CoroutineScope) {
    private val updateClient by lazy {
        client.config {
            followRedirects = false
            expectSuccess = false
        }
    }
    private val checkUpdatingMutex = MutexState()
    val checkUpdatingFlow
        get() = checkUpdatingMutex.state
    private val newVersionFlow = MutableStateFlow<NewVersion?>(null)
    private val downloadStatusFlow = MutableStateFlow<LoadStatus<File>?>(null)
    private var downloadJob: Job? = null
    val checkMessageFlow: StateFlow<String?>
        field: MutableStateFlow<String?> = MutableStateFlow(null)

    private val ignoreVersionListFlow by lazy {
        FileStateStore.createJsonFlow(
            key = "ignore_version_list",
            default = { emptySet<Int>() },
            scope = scope,
        )
    }
    private var lastManual = false

    val canRecheck get() = AppStore.storeFlow.value.let { settings ->
        XAdSkipUpdatePolicy.shouldAutoCheck(
            settings.autoCheckAppUpdate,
            settings.lastAppUpdateCheckAt,
            System.currentTimeMillis(),
        )
    }

    fun setAutoCheck(enabled: Boolean) {
        AppStore.updateSettings { it.copy(autoCheckAppUpdate = enabled) }
    }

    fun checkUpdate(
        manual: Boolean = false,
        @CallSite loc: String = "",
    ) {
        if (!manual && !canRecheck) return
        scope.launchLogged(Dispatchers.IO, loc = loc) {
            try {
                checkUpdatingMutex.tryWithStateLock {
                    lastManual = manual
                    checkMessageFlow.value = null
                    val newVersion = withTimeout(15_000) {
                        val response = updateClient.get(XAdSkipConfig.updateManifestUrl) {
                            onDownload { bytesReceived, _ ->
                                require(bytesReceived <= 256 * 1024) { "更新信息过大" }
                            }
                        }
                        require(response.status.value == 200) { "更新服务暂时不可用" }
                        XAdSkipUpdatePolicy.validate(response.body<NewVersion>())
                    }
                    AppStore.updateSettings { it.copy(lastAppUpdateCheckAt = System.currentTimeMillis()) }
                    if (!XAdSkipUpdatePolicy.isNewer(newVersion, META.versionCode)) {
                        newVersionFlow.value = null
                        checkMessageFlow.value = "当前已是最新版本（v${META.versionName}）"
                        if (manual) showCheckMessage(loc)
                        return@tryWithStateLock
                    }
                    checkMessageFlow.value = "发现新版本 v${newVersion.versionName}"
                    if (
                        !manual &&
                        (!AppStore.storeFlow.value.autoCheckAppUpdate ||
                            ignoreVersionListFlow.value.contains(newVersion.versionCode))
                    ) return@tryWithStateLock
                    newVersionFlow.value = newVersion
                }
            } catch (e: CancellationException) {
                // A bounded network timeout is a failed check, not a latest-version result.
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    checkMessageFlow.value = "检查更新超时，请稍后重试"
                    if (manual) showCheckMessage(loc)
                }
                throw e
            } catch (e: Exception) {
                checkMessageFlow.value = "检查更新失败，请检查网络后重试"
                if (manual) {
                    showCheckMessage(loc)
                }
                throw e
            }
        }
    }

    private fun showCheckMessage(loc: String) {
        val message = checkMessageFlow.value ?: return
        runMainPost { toast(message, loc = loc) }
    }

    private fun startDownload(newVersion: NewVersion) {
        if (downloadStatusFlow.value is LoadStatus.Loading || downloadJob?.isCompleted == false) return
        downloadStatusFlow.value = LoadStatus.Loading(0f)
        val apkFile = FolderUtils.sharedDir.resolve("XAdSkip-v${newVersion.versionCode}.apk").apply {
            if (exists()) {
                delete()
            }
        }
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val response =
                    updateClient.get(XAdSkipUpdatePolicy.downloadUrl(newVersion)) {
                        onDownload { bytesSentTotal, _ ->
                            require(bytesSentTotal <= newVersion.fileSize) { "安装包大小与更新信息不一致" }
                            val downloadStatus = downloadStatusFlow.value
                            if (downloadStatus is LoadStatus.Loading) {
                                downloadStatusFlow.value = LoadStatus.Loading(
                                    (bytesSentTotal.toFloat() / newVersion.fileSize).coerceIn(0f, 1f)
                                )
                            } else if (downloadStatus is LoadStatus.Failure) {
                                // 提前终止下载
                                downloadJob?.cancel()
                            }
                        }
                    }
                require(response.status.value == 200) { "安装包下载失败，请稍后重试" }
                val channel = response.bodyAsChannel()
                if (downloadStatusFlow.value is LoadStatus.Loading) {
                    channel.copyAndClose(apkFile.writeChannel())
                    XAdSkipUpdatePolicy.verifyDownload(newVersion, apkFile)
                    currentCoroutineContext().ensureActive()
                    downloadStatusFlow.value = LoadStatus.Success(apkFile)
                }
            } catch (e: CancellationException) {
                apkFile.delete()
                throw e
            } catch (e: Exception) {
                apkFile.delete()
                if (downloadStatusFlow.value is LoadStatus.Loading) {
                    downloadStatusFlow.value = LoadStatus.Failure(e)
                }
            } finally {
                downloadJob = null
            }
        }
    }

    @Composable
    fun UpgradeDialog() {
        newVersionFlow.collectAsStateWithLifecycle().value?.let { newVersionVal ->
            val text = remember(newVersionVal) {
                val logs = newVersionVal.versionLogs.takeWhile { v ->
                    v.code > META.versionCode
                }
                "v${META.versionName} -> v${newVersionVal.versionName}\n\n${
                    if (logs.size > 1) {
                        logs.joinToString("\n\n") { v -> "v${v.name}\n${v.desc}" }
                    } else if (logs.isNotEmpty()) {
                        logs.first().desc
                    } else {
                        ""
                    }
                }".trimEnd()
            }
            val scrollState = rememberScrollState()
            AppAlertDialog(
                title = {
                    Text(text = "XAdSkip 有新版本")
                },
                text = {
                    Text(
                        text = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(scrollState)
                    )
                },
                onDismissRequest = { },
                confirmButton = {
                    TextButton(onClick = {
                        newVersionFlow.value = null
                        startDownload(newVersionVal)
                    }) {
                        Text(text = "下载更新")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { newVersionFlow.value = null }) {
                        Text(text = "取消")
                    }
                    if (!lastManual) {
                        TextButton(onClick = {
                            newVersionFlow.value = null
                            ignoreVersionListFlow.update {
                                it + newVersionVal.versionCode
                            }
                            toast("已忽略此版本")
                        }) {
                            Text(text = "忽略")
                        }
                    }
                },
            )
        }

        downloadStatusFlow.collectAsStateWithLifecycle().value?.let { downloadStatusVal ->
            when (downloadStatusVal) {
                is LoadStatus.Loading -> {
                    AppAlertDialog(
                        title = { Text(text = "下载中") },
                        text = {
                            LinearProgressIndicator(
                                progress = { downloadStatusVal.progress },
                            )
                        },
                        onDismissRequest = {},
                        confirmButton = {
                            TextButton(onClick = {
                                downloadJob?.cancel()
                                downloadStatusFlow.value = null
                            }) {
                                Text(text = "终止下载")
                            }
                        },
                    )
                }

                is LoadStatus.Failure -> {
                    AppAlertDialog(
                        title = { Text(text = "下载失败") },
                        text = {
                            Text(text = downloadStatusVal.exception.let {
                                it.message ?: it.toString()
                            })
                        },
                        onDismissRequest = { downloadStatusFlow.value = null },
                        confirmButton = {
                            TextButton(onClick = {
                                downloadStatusFlow.value = null
                            }) {
                                Text(text = "关闭")
                            }
                        },
                    )
                }

                is LoadStatus.Success -> {
                    AppAlertDialog(
                        title = { Text(text = "下载完毕") },
                        text = {
                            Text(text = "可继续选择安装新版本")
                        },
                        onDismissRequest = {},
                        dismissButton = {
                            TextButton(onClick = {
                                downloadStatusFlow.value = null
                            }) {
                                Text(text = "关闭")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = throttle {
                                installApk(downloadStatusVal.result)
                            }) {
                                Text(text = "安装")
                            }
                        })
                }
            }
        }
    }
}


private fun installApk(file: File) {
    val uri = FileProvider.getUriForFile(
        app,
        "${app.packageName}.provider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setDataAndType(uri, "application/vnd.android.package-archive")
    }
    app.tryStartActivity(intent)
}
