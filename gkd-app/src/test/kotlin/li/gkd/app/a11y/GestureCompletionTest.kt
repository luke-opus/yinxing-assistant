package li.gkd.app.a11y

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureCompletionTest {
    @Test
    fun rejectedDispatchIsFailure() = runBlocking {
        assertFalse(GestureCompletion.awaitResult(100) { false })
    }

    @Test
    fun acceptedDispatchWaitsUntilCompletedCallback() = runBlocking {
        val callback = CompletableDeferred<(Boolean) -> Unit>()
        val result = async {
            GestureCompletion.awaitResult(2_000) { complete ->
                callback.complete(complete)
                true
            }
        }
        val complete = callback.await()
        assertFalse(result.isCompleted)
        complete(true)
        assertTrue(result.await())
    }

    @Test
    fun cancelledCallbackDoesNotCountAsCompletedGesture() = runBlocking {
        assertFalse(GestureCompletion.awaitResult(100) { complete ->
            complete(false)
            true
        })
    }

    @Test
    fun missingCallbackTimesOutAsFailure() = runBlocking {
        assertFalse(GestureCompletion.awaitResult(20) { true })
    }

    @Test
    fun duplicateCallbackKeepsFirstTerminalResult() = runBlocking {
        assertFalse(GestureCompletion.awaitResult(100) { complete ->
            complete(false)
            complete(true)
            true
        })
    }
}
