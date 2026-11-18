package li.gkd.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.gkd.app.service.A11yService
import li.gkd.app.util.IntentUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android 17 Settings regression, intentionally limited to the AOSP emulator.
 * Only Back is sent to Settings: no permission is granted, revoked, or bypassed.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37, maxSdkVersion = 37)
class A11ySetupActivityResultTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )

    @Test
    fun repairWaitsForBackThenFallsBackAndFinishesWithoutGranting() {
        val packageName = instrumentation.targetContext.packageName
        assertEquals("This test must not modify the installed release", "app.xadskip.android.debug", packageName)
        val appInfoActivity = resolveSettingsActivity(
            "android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName",
        )
        val accessibilityActivity = resolveSettingsActivity("android.settings.ACCESSIBILITY_SETTINGS")
        val originalServices = app.getSecureA11yServices()
        assertFalse(
            "Precondition: use an emulator where the debug service has not been enabled",
            originalServices.contains(A11yService.a11yCn),
        )
        val launched = shell(
            "am start -W -n $packageName/${MainActivity::class.java.name}",
        )
        assertTrue(launched, launched.contains("Status: ok"))
        awaitPage(MainActivity::class.java.name)
        lateinit var activity: MainActivity
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        }
        try {
            // Exercise the real ActivityResult failure path first. AOSP 17 protects this
            // activity; a failed launch must release the pending request for the fallback.
            assertThrows(SecurityException::class.java) {
                runBlocking {
                    withTimeout(10_000) {
                        activity.mainVm.activityResults.startActivity(
                            IntentUtils.a11ySettingsIntents().first(),
                        )
                    }
                }
            }
            instrumentation.runOnMainSync {
                // App-only onboarding fixture in the separate debug installation.
                if (!activity.mainVm.termsAcceptedFlow.value) {
                    activity.mainVm.acceptTermsStep(0)
                }
                activity.mainVm.requestA11ySetup(repairRestriction = true)
            }
            awaitPage(appInfoActivity)
            assertPageRemains(appInfoActivity)

            // A repeated tap while Settings is open must not enqueue a second journey.
            instrumentation.runOnMainSync {
                activity.mainVm.requestA11ySetup(repairRestriction = true)
            }
            pressBack()
            awaitPage(accessibilityActivity)
            assertPageRemains(accessibilityActivity)
            pressBack()
            awaitPage(MainActivity::class.java.name)
            assertPageRemains(MainActivity::class.java.name)
            assertEquals("Returning from Settings is not a grant", originalServices, app.getSecureA11yServices())
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                assertSame("Both ActivityResults must return to the original activity", activity, resumed)
                // A new request proves the completed journey released its guard.
                resumed.mainVm.requestA11ySetup()
            }
            awaitPage(accessibilityActivity)
            assertPageRemains(accessibilityActivity)
            pressBack()
            awaitPage(MainActivity::class.java.name)
            assertPageRemains(MainActivity::class.java.name)
            assertEquals(originalServices, app.getSecureA11yServices())
        } finally {
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun resumedActivity(): String = shell("dumpsys activity activities")
        .lineSequence()
        .firstOrNull { it.contains("topResumedActivity=") || it.contains("mResumedActivity:") }
        ?.trim()
        .orEmpty()

    private fun resolveSettingsActivity(actionAndData: String): String {
        val resolved = shell("cmd package resolve-activity --brief -a $actionAndData")
        return resolved.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("com.android.settings/") }
            ?: throw AssertionError("AOSP Settings activity unavailable: $resolved")
    }

    private fun awaitPage(activityName: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var resumed = ""
        while (SystemClock.uptimeMillis() < deadline) {
            resumed = resumedActivity()
            if (resumed.contains(activityName)) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected $activityName in foreground, got: $resumed")
    }

    private fun assertPageRemains(activityName: String) {
        // Catch premature RESULT_CANCELED from a task-changing intent and any queued
        // duplicate navigation, rather than merely observing one transient frame.
        repeat(4) {
            SystemClock.sleep(150)
            val resumed = resumedActivity()
            assertTrue("Expected to remain on $activityName, got: $resumed", resumed.contains(activityName))
        }
    }

    private fun pressBack() {
        shell("input keyevent KEYCODE_BACK")
    }
}
