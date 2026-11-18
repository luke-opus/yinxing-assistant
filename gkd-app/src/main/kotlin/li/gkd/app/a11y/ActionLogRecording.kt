package li.gkd.app.a11y

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.gkd.app.store.AppStore.actionCountFlow
import li.gkd.app.util.LogUtils
import li.gkd.db.ActionLog
import li.gkd.db.Db

object ActionLogRecording {
    private val mutex = Mutex()
    val lastWriteFailedFlow: StateFlow<Boolean>
        field = MutableStateFlow(false)

    suspend fun insert(record: ActionLog): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                Db.actionLogDao.insert(record)
                lastWriteFailedFlow.value = false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastWriteFailedFlow.value = true
                runCatching { LogUtils.d("触发记录保存失败", error) }
                return@withLock
            }
            // Retention and diagnostics must not turn a committed record into a failure.
            if (actionCountFlow.value % 100 == 0L) {
                try {
                    Db.actionLogDao.deleteKeepLatest()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    runCatching { LogUtils.d("旧触发记录清理失败", error) }
                }
            }
        }
    }
}
