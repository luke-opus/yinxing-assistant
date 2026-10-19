package li.gkd.app.ui.home

import li.gkd.app.platform.service.ServiceController
import li.gkd.app.service.switchAutomatorService
import li.gkd.app.store.AppStore
import li.gkd.app.ui.share.BaseViewModel

class DashboardVm : BaseViewModel() {
    fun pauseMatching() {
        AppStore.updateSettings { it.copy(enableMatch = false) }
    }

    fun resumeMatching() {
        AppStore.updateSettings { it.copy(enableMatch = true) }
    }

    fun startService() {
        resumeMatching()
        switchAutomatorService()
    }

    fun switchService() {
        switchAutomatorService()
    }

    fun startStatusService() {
        ServiceController.setStatusEnabled(true)
    }

    fun stopStatusService() {
        ServiceController.setStatusEnabled(false)
    }
}
