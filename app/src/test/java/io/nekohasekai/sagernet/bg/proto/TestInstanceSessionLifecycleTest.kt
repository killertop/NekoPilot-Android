package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestInstanceSessionLifecycleTest {

    @Test
    fun batchCreatesOneSharedSessionAndTestsEveryNode() = runBlocking {
        val created = mutableListOf<FakeSession>()
        val targets = listOf(ProxyEntity(id = 1), ProxyEntity(id = 2), ProxyEntity(id = 3))
        val tested = mutableListOf<Long>()

        TestInstance(
            profile = targets.first(),
            link = "https://example.invalid/",
            timeout = 1,
            sessionFactory = NodeTestSessionFactory { sessionTargets ->
                FakeSession(sessionTargets.map(ProxyEntity::id)).also(created::add)
            },
        ).runBatch(
            targets = targets,
            onResult = { target, _ -> tested += target.id },
            onError = { _, error -> throw AssertionError(error) },
        )

        assertEquals(listOf(1L, 2L, 3L), tested.sorted())
        assertEquals(1, created.size)
        assertEquals(listOf(1L, 2L, 3L), created.single().configuredTargets)
        assertEquals(listOf(1L, 2L, 3L), created.single().testedTargets.sorted())
        assertTrue(created.all(FakeSession::closed))
    }

    @Test
    fun batchReportsOneFailureAndContinuesTestingOtherNodes() = runBlocking {
        val targets = listOf(ProxyEntity(id = 1), ProxyEntity(id = 2), ProxyEntity(id = 3))
        val successes = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<Long>())

        TestInstance(
            profile = targets.first(),
            link = "https://example.invalid/",
            timeout = 1,
            sessionFactory = NodeTestSessionFactory { FakeSession(it.map(ProxyEntity::id), failingId = 2) },
        ).runBatch(
            targets = targets,
            onResult = { target, _ -> successes += target.id },
            onError = { target, _ -> failures += target.id },
        )

        assertEquals(listOf(1L, 3L), successes.sorted())
        assertEquals(listOf(2L), failures)
    }

    @Test
    fun largeBatchUsesBoundedSequentialCoreChunks() = runBlocking {
        val created = java.util.Collections.synchronizedList(mutableListOf<FakeSession>())
        val targets = (1L..65L).map { ProxyEntity(id = it) }

        TestInstance(
            profile = targets.first(),
            link = "https://example.invalid/",
            timeout = 1,
            sessionFactory = NodeTestSessionFactory { sessionTargets ->
                FakeSession(sessionTargets.map(ProxyEntity::id)).also(created::add)
            },
        ).runBatch(
            targets = targets,
            onResult = { _, _ -> Unit },
            onError = { _, error -> throw AssertionError(error) },
        )

        assertEquals(listOf(32, 32, 1), created.map { it.configuredTargets.size })
        assertEquals(targets.map(ProxyEntity::id), created.flatMap(FakeSession::configuredTargets))
        assertTrue(created.all(FakeSession::closed))
    }

    private class FakeSession(
        val configuredTargets: List<Long>,
        private val failingId: Long? = null,
    ) : NodeTestSession {
        val testedTargets = java.util.Collections.synchronizedList(mutableListOf<Long>())
        var closed = false

        override fun runNodeTest(target: ProxyEntity): UrlTestResult {
            testedTargets += target.id
            if (target.id == failingId) error("expected test failure")
            return UrlTestResult(latencyMs = target.id.toInt(), downloadMbps = null)
        }

        override fun close() {
            closed = true
        }
    }
}
