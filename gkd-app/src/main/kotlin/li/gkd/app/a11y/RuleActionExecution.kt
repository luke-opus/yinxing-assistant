package li.gkd.app.a11y

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import li.gkd.app.data.ActionResult
import li.gkd.app.data.ResolvedRule
import li.gkd.db.ActionLog
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Keeps the source page and completion record together for one already-started action. */
object RuleActionExecution {
    private class ActionOrigin(
        val context: CoroutineContext,
        val canStartAction: () -> Boolean,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ActionOrigin>

        fun check() {
            context.ensureActive()
            if (!canStartAction()) throw CancellationException("Rule engine stopped")
        }
    }

    suspend fun dispatchGuard(): () -> Unit {
        val context = currentCoroutineContext()
        return {
            context.ensureActive()
            context[ActionOrigin]?.check()
            Unit
        }
    }

    suspend fun checkCanDispatch() = dispatchGuard().invoke()

    fun capture(rule: ResolvedRule, origin: TopActivity, time: Long): ActionLog = ActionLog(
        appId = origin.appId,
        activityId = origin.activityId,
        subsId = rule.subsItem.id,
        subsVersion = rule.rawSubs.version,
        groupKey = rule.g.group.key,
        groupType = rule.g.group.groupType,
        ruleIndex = rule.index,
        ruleKey = rule.key,
        ctime = time,
    )

    suspend fun execute(
        record: ActionLog,
        perform: suspend () -> ActionResult,
        onSuccess: suspend (ActionLog, ActionResult) -> Unit,
        canStartAction: () -> Boolean = { true },
    ): ActionResult {
        // A stopped engine must not begin another action. Once dispatched, however,
        // cancelling its coroutine cannot undo a tap already delivered to another app.
        val origin = ActionOrigin(currentCoroutineContext(), canStartAction)
        origin.check()
        return withContext(NonCancellable + origin) {
            // Fallbacks check this original owner, not the NonCancellable bookkeeping job.
            perform().also { result ->
                if (result.result) onSuccess(record, result)
            }
        }
    }
}
