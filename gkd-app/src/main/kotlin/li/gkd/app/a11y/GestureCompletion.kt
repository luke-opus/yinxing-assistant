package li.gkd.app.a11y

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A dispatched gesture is only successful when Android reports completion. */
object GestureCompletion {
    suspend fun awaitResult(
        timeoutMillis: Long,
        dispatch: (complete: (Boolean) -> Unit) -> Boolean,
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        val accepted = dispatch { completed -> result.complete(completed) }
        if (!accepted) return false
        return withTimeoutOrNull(timeoutMillis) { result.await() } ?: false
    }
}
