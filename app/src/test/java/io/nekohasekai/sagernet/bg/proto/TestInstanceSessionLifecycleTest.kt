package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestInstanceSessionLifecycleTest {

    @Test
    fun batchCreatesAndClosesAnIndependentSessionForEveryNode() = runBlocking {
        val created = mutableListOf<FakeSession>()
        val targets = listOf(ProxyEntity(id = 1), ProxyEntity(id = 2), ProxyEntity(id = 3))
        val tested = mutableListOf<Long>()

        TestInstance(
            profile = targets.first(),
            link = "https://example.invalid/",
            timeout = 1,
            sessionFactory = NodeTestSessionFactory {
                FakeSession().also(created::add)
            },
        ).runBatch(
            targets = targets,
            onResult = { target, _ -> tested += target.id },
            onError = { _, error -> throw AssertionError(error) },
        )

        assertEquals(listOf(1L, 2L, 3L), tested)
        assertEquals(3, created.size)
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L)), created.map(FakeSession::targets))
        assertTrue(created.all(FakeSession::closed))
    }

    private class FakeSession : NodeTestSession {
        val targets = mutableListOf<Long>()
        var closed = false

        override fun runNodeTest(target: ProxyEntity): UrlTestResult {
            targets += target.id
            return UrlTestResult(latencyMs = target.id.toInt(), downloadMbps = null)
        }

        override fun close() {
            closed = true
        }
    }
}
