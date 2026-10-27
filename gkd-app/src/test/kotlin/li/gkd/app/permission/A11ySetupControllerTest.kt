package li.gkd.app.permission

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A11ySetupControllerTest {
    @Test
    fun normalRequestGoesStraightToAccessibilityAndReadsActualGrantOnReturn() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        var granted = false
        val controller = A11ySetupController(
            isGranted = { granted },
            readRestriction = { A11ySetupRestriction.NotRestricted },
            openSettings = {
                pages += it
                granted = true
            },
        )

        assertEquals(A11ySetupResult.Granted, controller.start())
        assertEquals(listOf(A11ySetupPage.Accessibility), pages)
    }

    @Test
    fun clearingKnownRestrictionContinuesFromAppInfoWithoutAnotherUserRequest() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        var restriction = A11ySetupRestriction.Restricted
        val controller = A11ySetupController(
            isGranted = { false },
            readRestriction = { restriction },
            openSettings = {
                pages += it
                if (it == A11ySetupPage.AppInfo) restriction = A11ySetupRestriction.NotRestricted
            },
        )

        assertEquals(A11ySetupResult.NotGranted, controller.start())
        assertEquals(listOf(A11ySetupPage.AppInfo, A11ySetupPage.Accessibility), pages)
    }

    @Test
    fun returningWithoutClearingKnownRestrictionStopsInsteadOfLooping() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        val controller = A11ySetupController(
            isGranted = { false },
            readRestriction = { A11ySetupRestriction.Restricted },
            openSettings = { pages += it },
        )

        assertEquals(A11ySetupResult.StillRestricted, controller.start())
        assertEquals(listOf(A11ySetupPage.AppInfo), pages)
    }

    @Test
    fun unknownRestrictionOnlyVisitsAppInfoForExplicitRepairAndNeverAssumesGrant() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        val controller = A11ySetupController(
            isGranted = { false },
            readRestriction = { A11ySetupRestriction.Unknown },
            openSettings = { pages += it },
        )

        assertEquals(A11ySetupResult.NotGranted, controller.start())
        assertEquals(listOf(A11ySetupPage.Accessibility), pages)
        pages.clear()

        assertEquals(A11ySetupResult.NotGranted, controller.start(repairRestriction = true))
        assertEquals(listOf(A11ySetupPage.AppInfo, A11ySetupPage.Accessibility), pages)
    }

    @Test
    fun grantedServiceSkipsSettingsUnlessReopeningADisconnectedService() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        val controller = A11ySetupController(
            isGranted = { true },
            readRestriction = { error("No restriction read is needed for an enabled service") },
            openSettings = { pages += it },
        )

        assertEquals(A11ySetupResult.Granted, controller.start(repairRestriction = true))
        assertTrue(pages.isEmpty())
        assertEquals(A11ySetupResult.Granted, controller.start(reopenEnabledService = true))
        assertEquals(listOf(A11ySetupPage.Accessibility), pages)
    }

    @Test
    fun grantingWhileInAppInfoDoesNotOpenAnotherSettingsPage() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        var granted = false
        val controller = A11ySetupController(
            isGranted = { granted },
            readRestriction = { A11ySetupRestriction.Restricted },
            openSettings = {
                pages += it
                granted = true
            },
        )

        assertEquals(A11ySetupResult.Granted, controller.start())
        assertEquals(listOf(A11ySetupPage.AppInfo), pages)
    }

    @Test
    fun repeatTapWhileSettingsIsOpenIsDroppedRatherThanQueued() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        val opened = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val controller = A11ySetupController(
            isGranted = { false },
            readRestriction = { A11ySetupRestriction.Unknown },
            openSettings = {
                pages += it
                if (it == A11ySetupPage.AppInfo) {
                    opened.complete(Unit)
                    returned.await()
                }
            },
        )
        val first = launch { controller.start(repairRestriction = true) }
        opened.await()

        assertEquals(A11ySetupResult.AlreadyInProgress, controller.start())
        returned.complete(Unit)
        first.join()
        assertEquals(listOf(A11ySetupPage.AppInfo, A11ySetupPage.Accessibility), pages)
    }

    @Test
    fun cancellationInAppInfoPreventsContinuationAndReleasesTheRequestLock() = runBlocking {
        val pages = mutableListOf<A11ySetupPage>()
        val opened = CompletableDeferred<Unit>()
        val controller = A11ySetupController(
            isGranted = { false },
            readRestriction = { A11ySetupRestriction.Unknown },
            openSettings = {
                pages += it
                if (it == A11ySetupPage.AppInfo) {
                    opened.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val request = launch { controller.start(repairRestriction = true) }
        opened.await()
        request.cancelAndJoin()

        assertEquals(listOf(A11ySetupPage.AppInfo), pages)
        assertEquals(A11ySetupResult.NotGranted, controller.start())
        assertEquals(listOf(A11ySetupPage.AppInfo, A11ySetupPage.Accessibility), pages)
    }
}
