package li.gkd.app.ui.home

import kotlinx.coroutines.Job
import li.gkd.app.core.state.Loadable
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.platform.service.ServiceController
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.service.A11yService
import li.gkd.app.service.currentAppUseA11y
import li.gkd.app.service.switchAutomatorService
import li.gkd.app.store.AppStore
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.util.launchLogged

class DashboardVm : BaseViewModel() {
    private var retryRulesJob: Job? = null

    fun retryRules() {
        if (retryRulesJob?.isActive == true) return
        if (SubscriptionRepository.snapshotFlow.value !is Loadable.Failure) return
        retryRulesJob = scope.launchLogged {
            SubscriptionRepository.initialize()
        }
    }

    fun pauseMatching() {
        AppStore.updateSettings { it.copy(enableMatch = false) }
    }

    fun resumeMatching(): Boolean {
        if (SubscriptionRepository.snapshotFlow.value !is Loadable.Ready) return false
        AppStore.updateSettings { it.copy(enableMatch = true) }
        return true
    }

    fun startService() {
        if (resumeMatching()) {
            switchAutomatorService()
        }
    }

    fun switchService() {
        val running = if (currentAppUseA11y) {
            A11yService.isRunning.value
        } else {
            uiAutomationFlow.value != null
        }
        if (!running && SubscriptionRepository.snapshotFlow.value !is Loadable.Ready) return
        switchAutomatorService()
    }

    fun startStatusService() {
        ServiceController.setStatusEnabled(true)
    }

    fun stopStatusService() {
        ServiceController.setStatusEnabled(false)
    }
}
