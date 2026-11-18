package li.gkd.app.a11y

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.gkd.app.data.ActionResult
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.subscription.UsedSubsEntry
import li.gkd.app.domain.rule.RuleSummaryBuilder
import li.gkd.db.ActionLog
import li.gkd.db.SubsItem
import li.gkd.db.SubsAppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RuleActionExecutionTest {
    private fun sourceRecord(): ActionLog {
        val subscription = RawSubscription.parse(
            """{id: 7, name: 'Test', version: 1, apps: [{id: 'source.app', groups: [
                {key: 1, name: 'Skip', rules: [{key: 2, name: 'Close launch screen', matches: '[text="跳过"]'}]}
            ]}]}""",
        )
        val summary = RuleSummaryBuilder.build(
            subscriptions = listOf(UsedSubsEntry(SubsItem(id = 7, enable = true, order = 0), subscription)),
            appInfoById = emptyMap(),
            appConfigs = listOf(SubsAppConfig(true, 7, "source.app")),
            groupConfigs = emptyList(),
            categoryConfigs = emptyList(),
        )
        val rule = summary.appIdToRules.getValue("source.app").single()
        return RuleActionExecution.capture(rule, TopActivity("source.app", "source.app.Ad"), 123, "Source app")
    }

    @Test
    fun navigationDuringSuccessfulActionRetainsSourceAppAndRuleInRecord() = runBlocking {
        val origin = sourceRecord()
        var foreground = "source.app"
        val recorded = mutableListOf<ActionLog>()
        RuleActionExecution.execute(
            record = origin,
            perform = {
                foreground = "destination.app"
                ActionResult("clickNode", true)
            },
            onSuccess = { record, _ -> recorded.add(record) },
        )
        assertEquals("destination.app", foreground)
        assertEquals("source.app", recorded.single().appId)
        assertEquals("source.app.Ad", recorded.single().activityId)
        assertEquals(123L, recorded.single().ctime)
        assertEquals(2, recorded.single().ruleKey)
        assertEquals("Source app", recorded.single().appName)
        assertEquals("Test", recorded.single().subsName)
        assertEquals("Skip", recorded.single().groupName)
        assertEquals("Close launch screen", recorded.single().ruleName)
        assertEquals("clickNode", recorded.single().action)
    }

    @Test
    fun firstSuccessRecordsActualFallbackAndCoordinatesWithoutOpeningLogPage() = runBlocking {
        val recorded = mutableListOf<ActionLog>()
        RuleActionExecution.execute(
            record = sourceRecord(),
            perform = { ActionResult("clickCenter", true, position = 720f to 84f) },
            onSuccess = { record, _ -> recorded.add(record) },
        )
        assertEquals(1, recorded.size)
        assertEquals("clickCenter", recorded.single().action)
        assertEquals(720f, recorded.single().positionX)
        assertEquals(84f, recorded.single().positionY)
    }

    @Test
    fun serviceCancellationAfterDispatchStillRecordsCompletionExactlyOnce() = runBlocking {
        withTimeout(2_000) {
            val origin = sourceRecord()
            val started = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val parent = Job()
            val recorded = mutableListOf<ActionLog>()
            var nextActionStarted = false
            val job = CoroutineScope(parent).launch {
                RuleActionExecution.execute(
                    record = origin,
                    perform = {
                        started.complete(Unit)
                        completed.await()
                        ActionResult("clickCenter", true)
                    },
                    onSuccess = { record, _ -> recorded.add(record) },
                )
                RuleActionExecution.execute(
                    record = origin,
                    perform = {
                        nextActionStarted = true
                        ActionResult("clickCenter", true)
                    },
                    onSuccess = { record, _ -> recorded.add(record) },
                )
            }
            started.await()
            parent.cancel()
            completed.complete(Unit)
            job.join()
            assertEquals(listOf(origin.copy(action = "clickCenter")), recorded)
            assertFalse(nextActionStarted)
        }
    }

    @Test
    fun alreadyCancelledEngineDoesNotStartAction() = runBlocking {
        val origin = sourceRecord()
        var dispatched = false
        val job = launch {
            currentCoroutineContext().cancel()
            RuleActionExecution.execute(
                origin,
                perform = {
                    dispatched = true
                    ActionResult("clickNode", true)
                },
                onSuccess = { _, _ -> error("Cancelled action must not be recorded") },
            )
        }
        job.join()
        assertFalse(dispatched)
    }

    @Test
    fun cancellationAfterFailedAttemptDoesNotDispatchFallbackWithinSameAction() = runBlocking {
        val origin = sourceRecord()
        var fallbackDispatched = false
        var recorded = false
        val job = launch {
            val owner = currentCoroutineContext()[Job]!!
            RuleActionExecution.execute(
                origin,
                perform = {
                    RuleActionExecution.checkCanDispatch()
                    // The node attempt failed while the service was being stopped.
                    owner.cancel()
                    RuleActionExecution.checkCanDispatch()
                    fallbackDispatched = true
                    ActionResult("clickCenter", true)
                },
                onSuccess = { _, _ -> recorded = true },
            )
        }
        job.join()
        assertFalse(fallbackDispatched)
        assertFalse(recorded)
    }

    @Test
    fun pausingAfterFailedAttemptDoesNotDispatchFallbackWithinSameAction() = runBlocking {
        val origin = sourceRecord()
        var engineRunning = true
        var fallbackDispatched = false
        val job = launch {
            RuleActionExecution.execute(
                origin,
                perform = {
                    RuleActionExecution.checkCanDispatch()
                    engineRunning = false
                    RuleActionExecution.checkCanDispatch()
                    fallbackDispatched = true
                    ActionResult("clickCenter", true)
                },
                onSuccess = { _, _ -> error("Paused fallback must not be recorded") },
                canStartAction = { engineRunning },
            )
        }
        job.join()
        assertFalse(fallbackDispatched)
    }

    @Test
    fun failedActionDoesNotCreateSuccessRecord() = runBlocking {
        var recorded = false
        val result = RuleActionExecution.execute(
            sourceRecord(),
            perform = { ActionResult("clickCenter", false) },
            onSuccess = { _, _ -> recorded = true },
        )
        assertFalse(result.result)
        assertFalse(recorded)
    }
}
