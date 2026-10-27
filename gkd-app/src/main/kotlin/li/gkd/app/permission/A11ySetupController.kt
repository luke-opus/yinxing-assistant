package li.gkd.app.permission

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

enum class A11ySetupRestriction {
    Restricted,
    NotRestricted,
    Unknown,
}

enum class A11ySetupPage {
    AppInfo,
    Accessibility,
}

enum class A11ySetupResult {
    Granted,
    NotGranted,
    StillRestricted,
    AlreadyInProgress,
}

/** One user-initiated settings journey. It never changes a system permission. */
class A11ySetupController(
    private val isGranted: () -> Boolean,
    private val readRestriction: () -> A11ySetupRestriction,
    private val openSettings: suspend (A11ySetupPage) -> Unit,
) {
    private val requestMutex = Mutex()

    suspend fun start(
        repairRestriction: Boolean = false,
        reopenEnabledService: Boolean = false,
    ): A11ySetupResult {
        // Drop repeat taps instead of queuing another settings journey.
        if (!requestMutex.tryLock()) return A11ySetupResult.AlreadyInProgress
        try {
            currentCoroutineContext().ensureActive()
            val alreadyGranted = isGranted()
            if (alreadyGranted && !reopenEnabledService) return A11ySetupResult.Granted
            if (!alreadyGranted &&
                (repairRestriction || readRestriction() == A11ySetupRestriction.Restricted)
            ) {
                openSettings(A11ySetupPage.AppInfo)
                currentCoroutineContext().ensureActive()
                if (isGranted()) return A11ySetupResult.Granted
                if (readRestriction() == A11ySetupRestriction.Restricted) {
                    return A11ySetupResult.StillRestricted
                }
                // AppInfo does not return a permission result. Unknown is not granted:
                // continue the explicitly requested journey once, then re-check the service.
            }
            openSettings(A11ySetupPage.Accessibility)
            currentCoroutineContext().ensureActive()
            return if (isGranted()) A11ySetupResult.Granted else A11ySetupResult.NotGranted
        } finally {
            requestMutex.unlock()
        }
    }
}
