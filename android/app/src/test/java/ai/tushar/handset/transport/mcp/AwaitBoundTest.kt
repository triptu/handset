package ai.tushar.handset.transport.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitBoundTest {
    private val listening = MutableStateFlow(false)
    private val problem = MutableStateFlow<String?>(null)

    @Test fun boundOnceHandsetHoldsThePort() = runTest {
        val bound = async { awaitBound(listening, problem, 3_000) }
        advanceTimeBy(500)
        listening.value = true
        assertTrue(bound.await())
    }

    @Test fun notBoundWhenAnotherAppHoldsThePort() = runTest {
        val bound = async { awaitBound(listening, problem, 3_000) }
        advanceTimeBy(500)
        problem.value = "Couldn't listen on port 8765"
        assertFalse(bound.await())
    }

    @Test fun notBoundWhenTheServerNeverComesUp() = runTest {
        assertFalse(awaitBound(listening, problem, 3_000))
    }
}
