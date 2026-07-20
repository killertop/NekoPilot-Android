package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeLatencyOrderTest {

    private data class Node(
        val id: Long,
        val status: Int = 0,
        val latencyMs: Int = 0,
        val originalOrder: Long = id,
    )

    private fun sort(nodes: List<Node>) = NodeLatencyOrder.sort(
        nodes,
        status = Node::status,
        latencyMs = Node::latencyMs,
        stableOrder = Node::originalOrder,
    ).map(Node::id)

    @Test
    fun movesEachSuccessfulResultIntoLatencyOrderImmediately() {
        assertEquals(
            listOf(2L, 1L, 3L),
            sort(
                listOf(
                    Node(id = 1),
                    Node(id = 2, status = 1, latencyMs = 120),
                    Node(id = 3),
                )
            ),
        )

        assertEquals(
            listOf(3L, 2L, 1L),
            sort(
                listOf(
                    Node(id = 1),
                    Node(id = 2, status = 1, latencyMs = 120),
                    Node(id = 3, status = 1, latencyMs = 42),
                )
            ),
        )
    }

    @Test
    fun keepsUntestedAndFailedNodesInStableOrderAfterSuccessfulNodes() {
        assertEquals(
            listOf(4L, 1L, 2L, 3L),
            sort(
                listOf(
                    Node(id = 3, status = 3),
                    Node(id = 1),
                    Node(id = 4, status = 1, latencyMs = 80),
                    Node(id = 2, status = 2),
                )
            ),
        )
    }
}
