// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.chronicle

import Cratis.Chronicle.Contracts.EventStores.Eventstores
import Cratis.Chronicle.Contracts.Namespaces.NamespacesOuterClass
import tools.jackson.databind.JsonNode
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.chronicle.ChronicleClient
import io.cratis.arc.contracts.chronicle.slices.Author
import io.cratis.arc.contracts.chronicle.slices.AuthorRegistered
import io.cratis.arc.contracts.chronicle.slices.BookReserved
import io.cratis.arc.contracts.chronicle.slices.ISBN
import io.cratis.arc.contracts.chronicle.slices.JavaPendingReservation
import io.cratis.arc.contracts.chronicle.slices.JavaReservationDueForExpiry
import io.cratis.arc.contracts.chronicle.slices.MemberId
import io.cratis.arc.contracts.chronicle.slices.PendingReservation
import io.cratis.arc.contracts.chronicle.slices.ReservationDueForExpiry
import io.cratis.arc.contracts.chronicle.slices.ReservationCancelled
import io.cratis.arc.contracts.chronicle.slices.ReservationExpired
import io.cratis.chronicle.ChronicleOptions
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.connection.ChronicleConnection
import io.cratis.chronicle.connection.ChronicleConnectionString
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/** Real-kernel proof for generated Kotlin and ordinary-Java Arc/Chronicle samples. */
class ArcChronicleRealKernelTest {
    private val objectMapper = ArcObjectMapper.create()
    @Test
    fun `generated Kotlin and Java endpoints preserve tenants concurrency and projected reads`() = runBlocking {
        try {
            verifySample(
                jarProperty = "arc.chronicle.kotlinSample.jar",
                eventStoreName = "ArcKotlinChronicleSample"
            )
            verifySample(
                jarProperty = "arc.chronicle.javaSample.jar",
                eventStoreName = "ArcJavaChronicleSample"
            )
        } catch (throwable: Throwable) {
            throw AssertionError("${throwable.message}\nKernel logs:\n${kernel.logs}", throwable)
        }
    }

    // Re-enabling this also means returning the samples' allAuthors to the observable
    // materialized.observeInstances / observeMaterializedInstancesPublisher; they are snapshots meanwhile.
    @Test
    @Disabled("Materialized ObserveInstances never emits a live page: MissingIdMapping on MongoDB, empty snapshots on InMemory (Cratis/Chronicle#4365); the client also asks for the second page (Cratis/Chronicle.Kotlin#106)")
    fun `generated Kotlin and Java author queries emit after registration`() = runBlocking {
        for ((jarProperty, storeName, queryName) in listOf(
            Triple("arc.chronicle.kotlinSample.jar", "ArcKotlinChronicleSample", "io.cratis.arc.samples.kotlin.chronicle.Author.allAuthors"),
            Triple("arc.chronicle.javaSample.jar", "ArcJavaChronicleSample", "io.cratis.arc.samples.javachronicle.Author.allAuthors")
        )) {
            val tenant = "author-" + UUID.randomUUID().toString().take(8)
            provision(storeName, tenant)
            val jar = checkNotNull(System.getProperty(jarProperty))
            SampleApplication.start(jar, connectionString).use { application ->
                application.assertLiveAuthors(tenant, UUID.randomUUID().toString().take(8), queryName)
            }
        }
    }

    /**
     * The Kotlin and Java tabs of the capstone end to end: the generated register-author command takes a
     * UUID-backed id (a `Guid` in the proxy), Chronicle appends under it, and the generated snapshot query
     * returns the projected author keyed by that id.
     */
    @Test
    fun `generated Kotlin and Java author queries return a registered author as a snapshot`() = runBlocking {
        try {
            for ((jarProperty, storeName) in listOf(
                "arc.chronicle.kotlinSample.jar" to "ArcKotlinChronicleSample",
                "arc.chronicle.javaSample.jar" to "ArcJavaChronicleSample"
            )) {
                val tenant = "snapshot-" + UUID.randomUUID().toString().take(8)
                provision(storeName, tenant)
                val jar = checkNotNull(System.getProperty(jarProperty)) { "Missing system property '$jarProperty'." }
                SampleApplication.start(jar, connectionString).use { application ->
                    val id = UUID.randomUUID().toString()
                    val name = "Registered " + id.take(8)
                    application.postCommand("/api/register-author", tenant, """{"id":"$id","name":"$name"}""")
                        .shouldSucceedWithoutResponse()
                    application.awaitAuthor(tenant, id, name)
                }
            }
        } catch (throwable: Throwable) {
            throw AssertionError("${throwable.message}\nKernel logs:\n${kernel.logs}", throwable)
        }
    }

    /**
     * The read models behind the Kotlin and Java tabs of the vertical-slice Automation page: projection
     * instances keyed by their event source (Chronicle#3924), the passive decision model resolved on
     * demand by that key - what Arc's command read-model resolver asks for - and closing events
     * removing the instance from both.
     */
    @Test
    fun `vertical-slice expiry read models are keyed and remove closed reservations`() = runBlocking {
        val eventStoreName = "ArcVerticalSlices" + UUID.randomUUID().toString().replace("-", "").take(8)
        val client = ChronicleClient(
            ChronicleOptions.fromConnectionString(connectionString)
                .withArtifactsFrom("io.cratis.arc.contracts.chronicle.slices")
        )
        try {
            val store = client.getEventStore(eventStoreName)
            store.awaitRegistration()
            val member = MemberId(UUID.randomUUID())
            val due = UUID.randomUUID().toString()
            val later = UUID.randomUUID().toString()
            val dueAt = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
            val laterAt = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
            assertTrue(store.eventLog.append(due, BookReserved(ISBN("978-0-00-000001-1"), member, dueAt)).isSuccess)
            assertTrue(store.eventLog.append(later, BookReserved(ISBN("978-0-00-000002-2"), member, laterAt)).isSuccess)

            assertEquals(
                PendingReservation(ISBN("978-0-00-000001-1"), member, dueAt),
                store.readModels.getInstanceByKey(PendingReservation::class, due)
            )
            assertEquals(
                PendingReservation(ISBN("978-0-00-000002-2"), member, laterAt),
                store.readModels.getInstanceByKey(PendingReservation::class, later)
            )
            val javaDue = store.readModels.getInstanceByKey(JavaPendingReservation::class, due)
            assertEquals(listOf("978-0-00-000001-1", member, dueAt),
                listOf(javaDue?.isbn?.value, javaDue?.memberId, javaDue?.expiresAt))
            val javaPending = store.readModels.getInstanceByKey(JavaPendingReservation::class, later)
            assertEquals(listOf("978-0-00-000002-2", member, laterAt),
                listOf(javaPending?.isbn?.value, javaPending?.memberId, javaPending?.expiresAt))
            awaitToDo(store, setOf(ReservationDueForExpiry(due, dueAt), ReservationDueForExpiry(later, laterAt)))

            assertTrue(store.eventLog.append(due, ReservationExpired(ISBN("978-0-00-000001-1"), member)).isSuccess)

            assertEquals(null, store.readModels.getInstanceByKey(PendingReservation::class, due))
            assertEquals(null, store.readModels.getInstanceByKey(JavaPendingReservation::class, due))
            awaitToDo(store, setOf(ReservationDueForExpiry(later, laterAt)))

            assertTrue(store.eventLog.append(later, ReservationCancelled(ISBN("978-0-00-000002-2"), member)).isSuccess)

            assertEquals(null, store.readModels.getInstanceByKey(PendingReservation::class, later))
            assertEquals(null, store.readModels.getInstanceByKey(JavaPendingReservation::class, later))
            awaitToDo(store, emptySet())
        } catch (throwable: Throwable) {
            throw AssertionError("${throwable.message}\nKernel logs:\n${kernel.logs}", throwable)
        } finally {
            client.dispose()
        }
    }

    /**
     * The author-list snapshot behind the Kotlin and Java tabs of the capstone and the State View page:
     * `readModels.getInstances` returns the projected author. The sink-backed
     * `readModels.materialized.getInstances(type, 0, 50)` is not used there: in Chronicle.Kotlin 6.7.0 it
     * asks the kernel for the second page and returns an empty list (Cratis/Chronicle.Kotlin#106).
     */
    @Test
    fun `author list snapshot returns the projected author`() = runBlocking {
        val eventStoreName = "ArcAuthors" + UUID.randomUUID().toString().replace("-", "").take(8)
        val client = ChronicleClient(
            ChronicleOptions.fromConnectionString(connectionString)
                .withArtifactsFrom("io.cratis.arc.contracts.chronicle.slices")
        )
        try {
            val store = client.getEventStore(eventStoreName)
            store.awaitRegistration()
            val id = UUID.randomUUID().toString()
            assertTrue(store.eventLog.append(id, AuthorRegistered("Ursula K. Le Guin")).isSuccess)
            val expected = listOf(Author(id, "Ursula K. Le Guin"))

            var replayed: List<Author> = emptyList()
            var attempts = 0
            while (attempts++ < 150) {
                replayed = store.readModels.getInstances(Author::class)
                if (replayed == expected) break
                delay(200)
            }
            assertEquals(expected, replayed, "readModels.getInstances did not return the projected author.")
        } catch (throwable: Throwable) {
            throw AssertionError("${throwable.message}\nKernel logs:\n${kernel.logs}", throwable)
        } finally {
            client.dispose()
        }
    }

    /** Waits for the active to-do projection, which Chronicle updates asynchronously. */
    private suspend fun awaitToDo(store: IEventStore, expected: Set<ReservationDueForExpiry>) {
        var kotlin: Set<ReservationDueForExpiry> = emptySet()
        var java: Set<ReservationDueForExpiry> = emptySet()
        repeat(150) {
            kotlin = store.readModels.getInstances(ReservationDueForExpiry::class).toSet()
            java = store.readModels.getInstances(JavaReservationDueForExpiry::class)
                .map { ReservationDueForExpiry(it.id, it.expiresAt) }.toSet()
            if (kotlin == expected && java == expected) return
            delay(200)
        }
        assertEquals(expected, kotlin, "The Kotlin to-do projection did not reach the expected instances.")
        assertEquals(expected, java, "The Java to-do projection did not reach the expected instances.")
    }

    private suspend fun verifySample(
        jarProperty: String,
        eventStoreName: String
    ) {
        val jar = checkNotNull(System.getProperty(jarProperty)) { "Missing system property '$jarProperty'." }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val tenantA = "a-$suffix"
        val tenantB = "b-$suffix"
        val taskId = "task-$suffix"
        provision(eventStoreName, tenantA, tenantB)

        SampleApplication.start(jar, connectionString).use { application ->
            val createdA = application.postCommand(
                "/api/create-task",
                tenantA,
                """{"id":"$taskId","title":"Tenant A title"}"""
            )
            createdA.shouldSucceedWithoutResponse()
            val initialA = application.awaitTask(tenantA, taskId) { it.path("title").asString() == "Tenant A title" }
            val initialPosition = initialA.path("eventLogPosition").asLong()

            val createdB = application.postCommand(
                "/api/create-task",
                tenantB,
                """{"id":"$taskId","title":"Tenant B title"}"""
            )
            createdB.shouldSucceedWithoutResponse()
            application.awaitTask(tenantB, taskId) { it.path("title").asString() == "Tenant B title" }

            val acceptedRename = application.postCommand(
                "/api/rename-task",
                tenantA,
                """{"id":"$taskId","title":"Accepted title","expectedSequenceNumber":$initialPosition}"""
            )
            acceptedRename.shouldSucceedWithoutResponse()
            application.awaitTask(tenantA, taskId) { it.path("title").asString() == "Accepted title" }

            val rejectedRename = application.postCommand(
                "/api/rename-task",
                tenantA,
                """{"id":"$taskId","title":"Rejected title","expectedSequenceNumber":$initialPosition}"""
            )
            assertEquals(400, rejectedRename.status)
            val validation = rejectedRename.json.path("validationResults").single {
                it.path("reason").asString() == "concurrencyViolation"
            }
            assertEquals(initialPosition, validation.path("state").path("expectedSequenceNumber").asLong())
            assertTrue(validation.path("state").path("actualSequenceNumber").asLong() > initialPosition)

            application.awaitTask(tenantA, taskId) { it.path("title").asString() == "Accepted title" }
            val finalB = application.awaitTask(tenantB, taskId) { it.path("title").asString() == "Tenant B title" }
            assertEquals("Tenant B title", finalB.path("title").asString())

            val allA = application.queryAll(tenantA)
            assertTrue(allA.json.path("isSuccess").asBoolean())
            assertEquals(listOf(taskId), allA.json.path("data").values().map { it.path("id").asString() })

            val directClient = ChronicleClient(ChronicleOptions.fromConnectionString(connectionString).withoutAutoRegistration())
            try {
                val tenantAStore = directClient.getEventStore(eventStoreName, tenantA)
                val tenantBStore = directClient.getEventStore(eventStoreName, tenantB)
                val tenantAEvents = tenantAStore.eventLog.getFromSequenceNumber(
                    EventSequenceNumber.first,
                    eventSourceId = taskId
                )
                val tenantBEvents = tenantBStore.eventLog.getFromSequenceNumber(
                    EventSequenceNumber.first,
                    eventSourceId = taskId
                )
                assertEquals(2, tenantAEvents.size)
                assertEquals(1, tenantBEvents.size)
                val accepted = tenantAEvents.single { it.content.contains("Accepted title") }
                val acceptedContent = objectMapper.readTree(accepted.content)
                assertEquals("Tenant A title", acceptedContent.path("previousTitle").asString())
                // The read path (Sequences.EventContext.toClient) drops eventSourceType,
                // eventStreamType and eventStreamId alike, so the returned context cannot be asserted
                // on - see #161. Filtering server-side is what proves the metadata was persisted.
                val metadataFiltered = tenantAStore.eventLog.getForEventSourceIdAndEventTypes(
                    eventSourceId = taskId,
                    eventTypes = emptyList(),
                    eventStreamType = "Tasks",
                    eventStreamId = "rename-stream",
                    eventSourceType = "Task"
                )
                assertEquals(1, metadataFiltered.size)
                assertTrue(metadataFiltered.single().content.contains("Accepted title"))
                assertFalse(tenantAEvents.any { it.content.contains("Rejected title") })
                assertTrue(tenantBEvents.single().content.contains("Tenant B title"))
            } finally {
                directClient.dispose()
            }
        }
    }

    private suspend fun provision(eventStoreName: String, vararg namespaces: String) {
        ChronicleConnection(ChronicleConnectionString.parse(connectionString)).use { connection ->
            connection.connect()
            val eventStoreResult = connection.services.eventStores.ensureEventStore(
                Eventstores.EnsureEventStoreRequest.newBuilder().setName(eventStoreName).build()
            )
            // Kernel 18.3.1 and earlier omitted `IsAuthorized` from the wire whenever it was true,
            // which a proto3 client decodes as false - every authorized command then looked denied.
            // Kernel 18.4.0 serializes it unconditionally, so this assertion is meaningful again and
            // pins the behaviour the JVM client depends on.
            check(eventStoreResult.isAuthorized &&
                eventStoreResult.validationResultsCount == 0 && eventStoreResult.exceptionMessagesCount == 0
            ) {
                "Could not provision event store '$eventStoreName' (authorized=${eventStoreResult.isAuthorized}, " +
                    "authorizationFailureReason='${eventStoreResult.authorizationFailureReason}'): " +
                    (eventStoreResult.exceptionMessagesList + eventStoreResult.validationResultsList.map { it.message })
                        .joinToString()
            }
            namespaces.forEach { namespace ->
                val namespaceResult = connection.services.namespaces.ensureNamespace(
                    NamespacesOuterClass.EnsureNamespaceRequest.newBuilder()
                        .setEventStore(eventStoreName)
                        .setNamespace(namespace)
                        .build()
                )
                // Same authorization guarantee as the event store check above.
                check(namespaceResult.isAuthorized &&
                    namespaceResult.validationResultsCount == 0 && namespaceResult.exceptionMessagesCount == 0
                ) {
                    "Could not provision namespace '$namespace' (authorized=${namespaceResult.isAuthorized}, " +
                        "authorizationFailureReason='${namespaceResult.authorizationFailureReason}'): " +
                        (namespaceResult.exceptionMessagesList + namespaceResult.validationResultsList.map { it.message })
                            .joinToString()
                }
            }
        }
    }

    private fun Exchange.shouldSucceedWithoutResponse() {
        assertEquals(200, status, "$body\n$appOutput")
        assertTrue(json.path("isSuccess").asBoolean(), "$body\n$appOutput")
        assertFalse(json.has("response"), "$body\n$appOutput")
    }

    private data class Exchange(
        val status: Int,
        val body: String,
        val json: JsonNode,
        val appOutput: String
    )

    private class SampleApplication private constructor(
        private val process: Process,
        private val output: StringBuffer,
        private val origin: String
    ) : AutoCloseable {
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val mapper = ArcObjectMapper.create()

        fun postCommand(path: String, tenant: String, json: String): Exchange = exchange(
            HttpRequest.newBuilder(URI.create("$origin$path"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header(TENANT_HEADER, tenant)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build()
        )

        /** Exercises the generated observable query over its actual WebSocket transport. */
        fun assertLiveAuthors(tenant: String, suffix: String, queryName: String) {
            val listener = AuthorFrames()
            val socket = http.newWebSocketBuilder().header(TENANT_HEADER, tenant)
                .buildAsync(URI.create(origin.replace("http://", "ws://") + "/.cratis/queries/ws"), listener)
                .get(10, TimeUnit.SECONDS)
            try {
                assertEquals("Connected", listener.next().path("type").asString())
                socket.sendText(
                    """{"type":"Subscribe","queryId":"authors","revision":1,"payload":{"queryName":"$queryName","arguments":{}}}""",
                    true
                ).get(5, TimeUnit.SECONDS)
                val initial = listener.nextResult()
                assertEquals("QueryResult", initial.path("type").asString(), initial.toString())
                assertTrue(initial.path("payload").path("isSuccess").asBoolean(), initial.toString())
                assertTrue(initial.path("payload").path("data").isArray, initial.toString())
                val id = UUID.randomUUID().toString()
                val name = "Registered $suffix"
                val command = postCommand("/api/register-author", tenant, """{"id":"$id","name":"$name"}""")
                assertEquals(200, command.status, "$command\n$output")
                assertTrue(command.json.path("isSuccess").asBoolean(), command.body)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                var update: JsonNode
                do {
                    update = listener.nextResult()
                    assertEquals("QueryResult", update.path("type").asString(), update.toString())
                    assertTrue(update.path("payload").path("isSuccess").asBoolean(), update.toString())
                    if (update.path("payload").path("data").any {
                        it.path("id").asString() == id && it.path("name").asString() == name
                    }) break
                } while (System.nanoTime() < deadline)
                assertTrue(update.path("payload").path("data").any {
                    it.path("id").asString() == id && it.path("name").asString() == name
                }, "No registered author in subsequent observable emission: $update\n$output")
            } finally {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            }
        }

        private inner class AuthorFrames : WebSocket.Listener {
            private val messages = LinkedBlockingQueue<JsonNode>()
            private val text = StringBuilder()
            override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*> {
                text.append(data)
                if (last) {
                    messages.add(mapper.readTree(text.toString()))
                    text.setLength(0)
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }
            fun next(): JsonNode = requireNotNull(messages.poll(20, TimeUnit.SECONDS)) {
                "No observable query frame; sample output:\n$output"
            }
            fun nextResult(): JsonNode {
                repeat(30) {
                    val frame = next()
                    if (frame.path("type").asString() != "Ping") return frame
                }
                error("Only keepalive frames received for observable query; sample output:\n$output")
            }
        }

        fun queryAll(tenant: String): Exchange = exchange(
            HttpRequest.newBuilder(URI.create("$origin/api/tasks"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header(TENANT_HEADER, tenant)
                .method("QUERY", HttpRequest.BodyPublishers.ofString("""{"arguments":{}}"""))
                .build()
        )

        /** Polls the snapshot author query until Chronicle has projected the registration. */
        suspend fun awaitAuthor(tenant: String, id: String, name: String) {
            var last: Exchange? = null
            repeat(300) {
                val response = exchange(
                    HttpRequest.newBuilder(URI.create("$origin/api/authors"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header(TENANT_HEADER, tenant)
                        .method("QUERY", HttpRequest.BodyPublishers.ofString("""{"arguments":{}}"""))
                        .build()
                )
                last = response
                assertEquals(200, response.status, "${response.body}\n$output")
                assertTrue(response.json.path("isSuccess").asBoolean(), "${response.body}\n$output")
                if (response.json.path("data").any { it.path("id").asString() == id && it.path("name").asString() == name }) return
                check(process.isAlive) { "Sample application exited while awaiting the author:\n$output" }
                delay(200)
            }
            throw AssertionError("The author query never returned '$id'. Last response: ${last?.body}\n$output")
        }

        suspend fun awaitTask(tenant: String, id: String, predicate: (JsonNode) -> Boolean): JsonNode {
            var lastResponse: Exchange? = null
            return try {
                withTimeout(60_000) {
                    var found: JsonNode? = null
                    while (found == null) {
                        val response = exchange(
                            HttpRequest.newBuilder(
                                URI.create("$origin/api/tasks/by-id?id=${URLEncoder.encode(id, StandardCharsets.UTF_8)}")
                            )
                                .timeout(Duration.ofSeconds(10))
                                .header(TENANT_HEADER, tenant)
                                .GET()
                                .build()
                        )
                        lastResponse = response
                        val data = response.json.path("data")
                        if (response.status == 200 && data.isObject && predicate(data)) found = data
                        if (found == null) {
                            check(process.isAlive) { "Sample application exited while awaiting projection:\n$output" }
                            delay(100)
                        }
                    }
                    found
                }
            } catch (exception: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(
                    "Timed out awaiting task '$id' for tenant '$tenant'. Last response: ${lastResponse?.body}\n$output",
                    exception
                )
            }
        }

        private fun exchange(request: HttpRequest): Exchange {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            val json = runCatching { mapper.readTree(body) }.getOrElse {
                error("Expected JSON from ${request.method()} ${request.uri()}, got ${response.statusCode()}: $body\n$output")
            }
            return Exchange(response.statusCode(), body, json, output.toString())
        }

        override fun close() {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(10, TimeUnit.SECONDS)) { "Sample application did not terminate after being killed." }
            }
        }

        companion object {
            private val portPattern = Pattern.compile("Tomcat started on port (\\d+)")

            fun start(jar: String, connectionString: String): SampleApplication {
                val process = ProcessBuilder(
                    System.getProperty("java.home") + "/bin/java",
                    "-jar",
                    jar,
                    "--server.port=0",
                    "--cratis.arc.expose-exception-details=true",
                    "--cratis.chronicle.connection-string=$connectionString"
                ).redirectErrorStream(true).start()
                val output = StringBuffer()
                val port = CompletableFuture<Int>()
                Thread({ consumeOutput(process, output, port) }, "arc-chronicle-sample-output").apply {
                    isDaemon = true
                    start()
                }
                val resolvedPort = try {
                    port.get(120, TimeUnit.SECONDS)
                } catch (exception: Exception) {
                    process.destroyForcibly()
                    process.waitFor(10, TimeUnit.SECONDS)
                    throw IllegalStateException("Sample application did not start:\n$output", exception)
                }
                return SampleApplication(process, output, "http://127.0.0.1:$resolvedPort")
            }

            private fun consumeOutput(process: Process, output: StringBuffer, readyPort: CompletableFuture<Int>) {
                var port: Int? = null
                var artifactsRegistered = false
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        val matcher = portPattern.matcher(line)
                        if (matcher.find()) port = matcher.group(1).toInt()
                        if (line.contains("Chronicle artifacts registered with event store")) artifactsRegistered = true
                        if (artifactsRegistered) port?.let(readyPort::complete)
                    }
                }
                if (!readyPort.isDone) {
                    readyPort.completeExceptionally(
                        IllegalStateException("Sample application exited with ${process.exitValue()} before Chronicle was ready.")
                    )
                }
            }
        }
    }

    companion object {
        private const val KERNEL_PORT = 35000
        private const val TENANT_HEADER = "x-cratis-tenant-id"
        private lateinit var kernel: GenericContainer<*>
        private lateinit var connectionString: String

        @JvmStatic
        @BeforeAll
        fun startKernel() {
            val image = checkNotNull(System.getProperty("arc.chronicle.kernel.image")) {
                "The chronicleRealKernelTest task must supply arc.chronicle.kernel.image."
            }
            require("@sha256:" in image) { "The Chronicle kernel image must be pinned by digest: '$image'." }
            try {
                kernel = GenericContainer<Nothing>(DockerImageName.parse(image)).apply {
                    withExposedPorts(KERNEL_PORT)
                    waitingFor(
                        Wait.forHttp("/health")
                            .forPort(KERNEL_PORT)
                            .usingTls()
                            .allowInsecure()
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(3))
                    )
                }
                kernel.start()
            } catch (throwable: Throwable) {
                if (::kernel.isInitialized) runCatching { kernel.stop() }
                throw IllegalStateException(
                    "chronicleRealKernelTest requires Docker and the pinned Chronicle image '$image'.",
                    throwable
                )
            }
            connectionString =
                "chronicle://chronicle-dev-client:chronicle-dev-secret@${kernel.host}:${kernel.getMappedPort(KERNEL_PORT)}"
        }

        @JvmStatic
        @AfterAll
        fun stopKernel() {
            if (::kernel.isInitialized) kernel.stop()
        }
    }
}
