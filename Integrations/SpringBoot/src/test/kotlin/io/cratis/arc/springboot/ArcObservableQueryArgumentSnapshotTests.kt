// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultObservableQueryEmissionGuards
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard
import io.cratis.arc.queries.GuardObservableQueryEmission
import io.cratis.arc.queries.ObservableQueryEmissionVerdict
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryHubMessage
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.ObservableQuerySubscriptionRequest
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryHealthTracker
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.ValidationResultSeverity
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.core.convert.support.DefaultConversionService

internal class ArcObservableQueryArgumentSnapshotTests {
    @Test
    fun `async subscription rebinds typed scalars and boxed arrays from the immutable input`() {
        Fixture(scalarParameters + arrayParameters).use { fixture ->
            val input = linkedMapOf<String, String?>(
                "id" to ID.toString(), "date" to "2026-06-11", "concept" to ID.toString(),
                "ordinary" to "SECOND", "coded" to "42", "small" to "1",
                "ids" to ID.toString(), "longs" to "1", "nullable" to null
            )
            val request = ObservableQuerySubscriptionRequest(
                fixture.performer.fullyQualifiedName.value, input, 2, 7, "value", "desc", ObservableQueryTransferMode.DELTA
            )
            fixture.gateRunner {
                fixture.subscribe(request)
                input.clear()
                input["id"] = UUID.randomUUID().toString()
            }
            val captured = fixture.awaitInvocation()
            val arguments = captured.request.arguments
            assertEquals(ID, arguments["id"] as UUID)
            assertEquals(LocalDate.of(2026, 6, 12), (arguments["date"] as LocalDate).plusDays(1))
            assertEquals(ID, (arguments["concept"] as SnapshotConcept).value())
            assertSame(SnapshotOrdinary.SECOND, arguments["ordinary"])
            assertSame(SnapshotCoded.SECOND, arguments["coded"])
            assertEquals(2L, (arguments["small"] as Long) + 1L)
            assertEquals(UUID::class.java, arguments["ids"]!!.javaClass.componentType)
            assertEquals(Long::class.javaObjectType, arguments["longs"]!!.javaClass.componentType)
            assertEquals(ID, (arguments["ids"] as Array<*>).single())
            assertEquals(1L, (arguments["longs"] as Array<*>).single())
            assertTrue(arguments.containsKey("nullable"))
            assertNull(arguments["nullable"])
            assertFalse(arguments.containsKey("omitted"))
            assertEquals(2, captured.request.paging.page)
            assertEquals(7, captured.request.paging.pageSize)
            assertEquals("value", captured.request.sorting.field)
            assertEquals(QuerySortDirection.DESCENDING, captured.request.sorting.direction)
            assertSame(fixture.principal, captured.options.principal)
            assertEquals("tenant", captured.options.tenantId)
            assertEquals("tenant", captured.options.tenantNamespace)
            assertEquals(fixture.correlation, captured.options.correlationId)
            assertEquals(ValidationResultSeverity.Information, captured.options.allowedValidationSeverity)
            assertEquals(ObservableQueryTransferMode.DELTA, captured.mode)
            assertEquals(8L, fixture.messages.poll(5, TimeUnit.SECONDS)?.revision)
            java.lang.reflect.Array.set(arguments["ids"], 0, UUID.randomUUID())
            fixture.subscribe(request, "second")
            val second = fixture.awaitInvocation().request.arguments
            assertNotSame(arguments["ids"], second["ids"])
            assertEquals(ID, (second["ids"] as Array<*>).single())
        }
    }

    @Test
    fun `Q1 rebound concepts and arrays remain typed and independent for two guards`() {
        val observed = LinkedBlockingQueue<Map<String, Any?>>()
        val guards = listOf(
            BlockingObservableQueryEmissionGuard {
                observed.add(it.arguments)
                java.lang.reflect.Array.set(it.arguments["ids"], 0, UUID.randomUUID())
                java.lang.reflect.Array.set(it.arguments["longs"], 0, 99L)
                ObservableQueryEmissionVerdict.ALLOW
            },
            BlockingObservableQueryEmissionGuard { observed.add(it.arguments); ObservableQueryEmissionVerdict.ALLOW }
        )
        Fixture(listOf(ParameterDescriptor("concept", SnapshotConcept::class.java.name)) + arrayParameters, guards = guards).use { fixture ->
            fixture.subscribe(ObservableQuerySubscriptionRequest(fixture.performer.fullyQualifiedName.value,
                mapOf("concept" to ID.toString(), "ids" to ID.toString(), "longs" to "1")))
            val opening = fixture.awaitInvocation().request.arguments
            val first = requireNotNull(observed.poll(5, TimeUnit.SECONDS))
            val second = requireNotNull(observed.poll(5, TimeUnit.SECONDS))
            assertEquals(UUID::class.java, second["ids"]!!.javaClass.componentType)
            assertEquals(Long::class.javaObjectType, second["longs"]!!.javaClass.componentType)
            assertEquals(ID, (second["ids"] as Array<*>).single())
            assertEquals(1L, (second["longs"] as Array<*>).single())
            assertEquals(ID, (second["concept"] as SnapshotConcept).value())
            assertNotSame(first["concept"], second["concept"])
            assertNotSame(opening["concept"], second["concept"])
            assertEquals(ID, (opening["ids"] as Array<*>).single())
            assertEquals(1L, (opening["longs"] as Array<*>).single())
        }
    }

    @Test
    fun `canonical collection and legacy mutable collection aliases bind typed elements`() {
        val parameters = listOf(
            ParameterDescriptor("canonical", TypeShapeDescriptor.sequence(SequenceKind.COLLECTION, TypeShapeDescriptor.value("java.util.UUID"))),
            ParameterDescriptor("mutable", "kotlin.collections.MutableCollection<java.util.UUID>")
        )
        Fixture(parameters).use { fixture ->
            fixture.subscribe(ObservableQuerySubscriptionRequest(
                fixture.performer.fullyQualifiedName.value, mapOf("canonical" to ID.toString(), "mutable" to ID.toString())
            ))
            val arguments = fixture.awaitInvocation().request.arguments
            parameters.forEach { parameter ->
                val element = (arguments[parameter.name] as Collection<*>).single()
                assertEquals(UUID::class.java, element!!.javaClass)
                assertEquals(ID, element)
            }
        }
    }

    @Test
    fun `deterministic application converter receives accepted text again rather than serialized JSON`() {
        val conversions = AtomicInteger()
        val conversion = DefaultConversionService().apply {
            addConverter(String::class.java, UUID::class.java) { text ->
                require(text == "accepted-id")
                conversions.incrementAndGet()
                ID
            }
        }
        Fixture(listOf(ParameterDescriptor("id", "java.util.UUID")), conversion).use { fixture ->
            fixture.subscribe(ObservableQuerySubscriptionRequest(fixture.performer.fullyQualifiedName.value, mapOf("id" to "accepted-id")))
            assertEquals(ID, fixture.awaitInvocation().request.arguments["id"])
            assertEquals(2, conversions.get())
        }
    }

    @Test
    fun `malformed ambiguous and unknown values fail before reserving subscription state`() {
        Fixture(listOf(ParameterDescriptor("id", "java.util.UUID"))).use { fixture ->
            listOf(mapOf("id" to "invalid"), mapOf("unknown" to "x"), mapOf("id" to null),
                mapOf("id" to ID.toString(), "ID" to ID.toString())).forEach { arguments ->
                assertEquals(HubSubscribeResult.MALFORMED, fixture.transport.subscribe(
                    fixture.connection, "invalid", 1, ObservableQuerySubscriptionRequest(fixture.performer.fullyQualifiedName.value, arguments)
                ))
                assertEquals(0, fixture.connection.subscriptions.count)
            }
            assertTrue(fixture.invocations.isEmpty())
        }
    }

    @Test
    fun `subscription severity retains explicit overrides and selects per-query defaults`() {
        listOf(false, true).forEach { warningsAsErrors ->
            listOf(null, ValidationResultSeverity.Warning).forEach { override ->
                Fixture(emptyList(), warningsAsErrors = warningsAsErrors, severity = override).use { fixture ->
                    fixture.subscribe(ObservableQuerySubscriptionRequest(fixture.performer.fullyQualifiedName.value))
                    val expected = override ?: ValidationResultSeverity.Information.takeIf { warningsAsErrors }
                    assertEquals(expected, fixture.awaitInvocation().options.allowedValidationSeverity)
                }
            }
        }
    }

    private class Fixture(
        parameters: List<ParameterDescriptor>,
        conversion: DefaultConversionService = DefaultConversionService(),
        warningsAsErrors: Boolean = false,
        severity: ValidationResultSeverity? = ValidationResultSeverity.Information,
        guards: List<GuardObservableQueryEmission> = emptyList()
    ) : AutoCloseable {
        val invocations = LinkedBlockingQueue<Invocation>()
        val messages = LinkedBlockingQueue<ObservableQueryHubMessage>()
        val principal = ArcPrincipal("alice", true, setOf("reader"), "alice")
        val correlation: UUID = UUID.randomUUID()
        val performer = object : QueryPerformer {
            override val fullyQualifiedName = FullyQualifiedQueryName("snapshot.observe")
            override val descriptor = QueryDescriptor("observe", "snapshot", "kotlin.String", parameters = parameters, transport = QueryTransportType.OBSERVABLE, treatWarningsAsErrors = warningsAsErrors)
            override suspend fun perform(context: QueryContext): Any = MutableStateFlow("ok")
        }
        private val registry = ConcurrentQueryPerformerRegistry().apply { register(performer) }
        private val mapper = ArcObjectMapper.create()
        private val realPipeline = DefaultObservableQueryPipeline(registry, emissionGuards = DefaultObservableQueryEmissionGuards(guards, mapper))
        private val pipeline = object : ObservableQueryPipeline {
            override suspend fun open(request: QueryRequest, options: QueryExecutionOptions, transferMode: ObservableQueryTransferMode?, keyExtractor: ((Any) -> Any?)?) =
                realPipeline.open(request, options, transferMode, keyExtractor).also {
                    invocations.add(Invocation(request, options, transferMode))
                }
        }
        private val scope = ArcApplicationCoroutineScope(1, 8)
        val transport = ArcObservableQueryTransport(
            registry, pipeline, mock(ServiceResolver::class.java),
            ArcQueryRequestBinder(mapper, conversion, javaClass.classLoader), mapper, scope, ArcProperties(), true,
            mock(ArcPrincipalFactory::class.java), mock(ArcTenantResolutionService::class.java), mock(QueryHealthTracker::class.java)
        )
        val connection = transport.createHubConnection("ws-snapshot", ArcObservableHandshake(
            principal, "tenant", correlation, emptyMap(), severity
        ), { messages.add(it) }, {})

        fun subscribe(request: ObservableQuerySubscriptionRequest, id: String = "first") {
            assertEquals(HubSubscribeResult.ACCEPTED, transport.subscribe(connection, id, 8, request))
        }

        fun awaitInvocation(): Invocation = requireNotNull(invocations.poll(5, TimeUnit.SECONDS)) { "No pipeline invocation; messages=$messages" }

        fun gateRunner(accept: () -> Unit) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocker = requireNotNull(scope.tryLaunch { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                accept()
                assertTrue(invocations.isEmpty())
            } finally {
                release.countDown()
                kotlinx.coroutines.runBlocking { blocker.join() }
            }
        }

        override fun close() {
            connection.close()
            transport.close()
            scope.close()
        }
    }

    private data class Invocation(val request: QueryRequest, val options: QueryExecutionOptions, val mode: ObservableQueryTransferMode?)

    private companion object {
        val ID: UUID = UUID.fromString("05bac18d-3e07-4c42-9cb8-85cc341da007")
        val scalarParameters = listOf(
            ParameterDescriptor("id", "java.util.UUID"), ParameterDescriptor("date", "java.time.LocalDate"),
            ParameterDescriptor("concept", SnapshotConcept::class.java.name), ParameterDescriptor("ordinary", SnapshotOrdinary::class.java.name),
            ParameterDescriptor("coded", SnapshotCoded::class.java.name), ParameterDescriptor("small", "kotlin.Long"),
            ParameterDescriptor("nullable", "kotlin.Long", true), ParameterDescriptor("omitted", "kotlin.Long", true)
        )
        val arrayParameters = listOf(
            ParameterDescriptor("ids", TypeShapeDescriptor.sequence(SequenceKind.ARRAY, TypeShapeDescriptor.value("java.util.UUID"))),
            ParameterDescriptor("longs", TypeShapeDescriptor.sequence(SequenceKind.ARRAY, TypeShapeDescriptor.value("kotlin.Long")))
        )
    }
}

internal data class SnapshotConcept(private val value: UUID) : ConceptAs<UUID> {
    override fun value(): UUID = value
}
internal enum class SnapshotOrdinary { FIRST, SECOND }
internal enum class SnapshotCoded : ArcEnum {
    FIRST, SECOND;
    override fun value(): Int = if (this == FIRST) 7 else 42
}
