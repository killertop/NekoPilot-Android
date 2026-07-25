package io.nekohasekai.sagernet.group

import java.io.File
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDiagnosticsTest {

    @Test
    fun classifiesTypedFailuresWithoutParsingLocalizedText() {
        assertEquals(
            SubscriptionFailureKind.AUTH,
            subscriptionFailureKind(SubscriptionHttpException(401, "text/html", 12L)),
        )
        assertEquals(
            SubscriptionFailureKind.HTTP,
            subscriptionFailureKind(SubscriptionHttpException(404, "text/plain", 0L)),
        )
        assertEquals(
            SubscriptionFailureKind.NO_NODES,
            subscriptionFailureKind(SubscriptionNoNodesException("text/html", 128L)),
        )
        assertEquals(
            R.string.subscription_update_no_nodes_error,
            subscriptionFailureMessageRes(SubscriptionNoNodesException()),
        )
        assertEquals(
            SubscriptionFailureKind.DNS,
            subscriptionFailureKind(UnknownHostException("subscription.example")),
        )
        assertEquals(
            SubscriptionFailureKind.TIMEOUT,
            subscriptionFailureKind(SocketTimeoutException("read timed out")),
        )
    }

    @Test
    fun fileStoreRoundTripsOnlySanitizedDiagnosticFields() {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "nekopilot-subscription-diagnostics-${System.nanoTime()}",
        )
        val store = SubscriptionDiagnosticsFileStore(directory)
        val record = SubscriptionFailureRecord(
            kind = SubscriptionFailureKind.AUTH,
            technicalMessage = "The token was redacted",
            occurredAtSeconds = 123L,
            httpStatus = 403,
            contentType = "text/html",
            responseBytes = 42L,
        )

        try {
            store.write(7L, record)

            assertEquals(record, store.read(7L))
            assertTrue(File(directory, "7.properties").isFile)

            store.clear(7L)

            assertNull(store.read(7L))
            assertFalse(File(directory, "7.properties").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedDiagnosticFileIsIgnored() {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "nekopilot-subscription-diagnostics-${System.nanoTime()}",
        )
        val store = SubscriptionDiagnosticsFileStore(directory)
        try {
            directory.mkdirs()
            File(directory, "9.properties").writeText("occurredAtSeconds=not-a-number")

            assertNull(store.read(9L))
            assertFalse(File(directory, "9.properties").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
