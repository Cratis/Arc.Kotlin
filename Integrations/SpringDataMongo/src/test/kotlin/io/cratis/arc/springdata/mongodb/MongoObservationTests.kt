// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.BsonDocument
import org.bson.BsonString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate

class MongoObservationTests {
    private lateinit var server: MongoServer
    private lateinit var client: com.mongodb.client.MongoClient
    private lateinit var template: MongoTemplate

    @BeforeEach
    fun setUp() {
        server = MongoServer(MemoryBackend())
        val address = server.bind()
        client = com.mongodb.client.MongoClients.create("mongodb://${address.hostString}:${address.port}")
        template = MongoTemplate(client, "arc-observe")
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.shutdownNow()
    }

    @Test
    fun `list observation emits initial insert and delete snapshots in order and preserves tenant`() = runBlocking {
        val watcher = FakeWatcher()
        val observed = MongoObservableQuery(MongoOperationsResolver { tenant ->
            assertEquals("tenant-a", tenant)
            template
        }, watcher)
        val snapshots = observed.observe(MongoTaskReadModel::class.java, tenantId = "tenant-a").produceIn(this)

        assertEquals(emptyList<MongoTaskReadModel>(), withTimeout(2_000) { snapshots.receive() })
        withTimeout(2_000) { watcher.started.await() }
        template.save(MongoTaskReadModel("one", "First"))
        watcher.emit(MongoChangeOperation.INSERT, "one", "tenant-a")
        assertEquals(listOf("one"), withTimeout(2_000) { snapshots.receive() }.map(MongoTaskReadModel::getId))

        template.remove(MongoTaskReadModel("one", "First"))
        watcher.emit(MongoChangeOperation.DELETE, "one", "tenant-a")
        assertEquals(emptyList<MongoTaskReadModel>(), withTimeout(2_000) { snapshots.receive() })
        assertEquals("tenant-a", watcher.tenantId)
        snapshots.cancel()
        observed.close()
    }

    @Test
    fun `watcher reconnects with its resume token and closes every cursor on cancellation`() = runBlocking {
        val opens = AtomicInteger()
        val closedCursors = AtomicInteger()
        val openedTokens = mutableListOf<BsonDocument?>()
        val firstToken = BsonDocument("token", BsonString("one"))
        val source = MongoChangeStreamSource { _, tenantId, token ->
            assertEquals("tenant-a", tenantId)
            openedTokens.add(token)
            val open = opens.incrementAndGet()
            if (open == 1) error("transient open failure")
            object : MongoChangeStreamCursor {
                private var delivered = false
                override fun next(): MongoChange? {
                    if (delivered) {
                        if (open == 2) error("transient cursor failure")
                        return null
                    }
                    delivered = true
                    return MongoChange(
                        MongoChangeOperation.INSERT,
                        "tasks",
                        tenantId,
                        BsonDocument("_id", BsonString("wanted")),
                        if (open == 2) firstToken else BsonDocument("token", BsonString("two"))
                    )
                }

                override fun close() {
                    closedCursors.incrementAndGet()
                }
            }
        }
        val watcher = ReconnectingMongoChangeStreamWatcher(
            source,
            MongoObservationOptions(
                initialReconnectDelay = Duration.ofMillis(1),
                maximumReconnectDelay = Duration.ofMillis(2),
                cursorAwaitTime = Duration.ofMillis(1),
                bufferCapacity = 0
            )
        )

        val changes = withTimeout(2_000) {
            watcher.watch(MongoTaskReadModel::class.java, "tenant-a", "wanted").take(2).toList()
        }

        assertEquals(2, changes.size)
        assertEquals(listOf(null, null, firstToken), openedTokens)
        assertEquals(3, opens.get())
        assertEquals(2, closedCursors.get())
    }

    @Test
    fun `positive-capacity watcher stays within its configured bound and closes on cancellation`() = runBlocking {
        val capacity = 2
        val reads = AtomicInteger()
        val cursorClosed = AtomicBoolean()
        val source = MongoChangeStreamSource { _, tenantId, _ ->
            object : MongoChangeStreamCursor {
                override fun next(): MongoChange = MongoChange(
                    MongoChangeOperation.UPDATE,
                    "tasks",
                    tenantId,
                    BsonDocument("_id", BsonString(reads.incrementAndGet().toString())),
                    null
                )

                override fun close() {
                    cursorClosed.set(true)
                }
            }
        }
        val watcher = ReconnectingMongoChangeStreamWatcher(
            source,
            MongoObservationOptions(
                initialReconnectDelay = Duration.ofMillis(1),
                maximumReconnectDelay = Duration.ofMillis(2),
                cursorAwaitTime = Duration.ofMillis(1),
                bufferCapacity = capacity
            )
        )
        val receivedFirst = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collection = launch {
            watcher.watch(MongoTaskReadModel::class.java, null, null).collect {
                receivedFirst.complete(Unit)
                releaseCollector.await()
            }
        }

        withTimeout(2_000) { receivedFirst.await() }
        delay(50)
        assertTrue(reads.get() <= capacity + 2, "Cursor read ${reads.get()} items for capacity $capacity")
        collection.cancelAndJoin()
        assertTrue(cursorClosed.get())
    }

    @Test
    fun `zero-capacity watcher does not run an unbounded distance ahead of a slow collector`() = runBlocking {
        val reads = AtomicInteger()
        val source = MongoChangeStreamSource { _, tenantId, _ ->
            object : MongoChangeStreamCursor {
                override fun next(): MongoChange = MongoChange(
                    MongoChangeOperation.UPDATE,
                    "tasks",
                    tenantId,
                    BsonDocument("_id", BsonString(reads.incrementAndGet().toString())),
                    null
                )

                override fun close() = Unit
            }
        }
        val watcher = ReconnectingMongoChangeStreamWatcher(
            source,
            MongoObservationOptions(
                initialReconnectDelay = Duration.ofMillis(1),
                maximumReconnectDelay = Duration.ofMillis(2),
                cursorAwaitTime = Duration.ofMillis(1),
                bufferCapacity = 0
            )
        )
        val received = Channel<MongoChange>(Channel.RENDEZVOUS)
        val collection = launch {
            watcher.watch(MongoTaskReadModel::class.java, null, null).collect(received::send)
        }

        withTimeout(2_000) { received.receive() }
        assertTrue(reads.get() <= 2)
        collection.cancelAndJoin()
    }

    private class FakeWatcher : MongoChangeStreamWatcher {
        private val changes = MutableSharedFlow<MongoChange>(extraBufferCapacity = 1)
        val started = CompletableDeferred<Unit>()
        var tenantId: String? = null

        override fun watch(documentType: Class<*>, tenantId: String?, documentKey: Any?): Flow<MongoChange> = flow {
            this@FakeWatcher.tenantId = tenantId
            started.complete(Unit)
            emitAll(changes)
        }

        suspend fun emit(operation: MongoChangeOperation, id: String, tenantId: String?) {
            changes.emit(
                MongoChange(
                    operation,
                    "tasks",
                    tenantId,
                    BsonDocument("_id", BsonString(id)),
                    null
                )
            )
        }
    }
}
