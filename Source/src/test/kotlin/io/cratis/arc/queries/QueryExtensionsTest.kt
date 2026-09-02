// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CannotResolveCommandDependency
import io.cratis.arc.commands.CommandHandlerArgumentResolver
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.results.PagingInfo
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class QueryExtensionsTest {
    private val queryName = FullyQualifiedQueryName("Tests.Models.all")
    private val principal = ArcPrincipal("Ada", true, setOf("reader"), "user-42")
    private val request = QueryRequest(queryName)

    @Test
    fun `default renderers page sort and preserve total item count for iterable results`() = runBlocking {
        val context = queryContext(
            QueryRequest(
                queryName,
                paging = QueryPaging(1, 2),
                sorting = QuerySorting("value", QuerySortDirection.DESCENDING)
            )
        )
        val values = listOf(Model(1, "a"), Model(2, "c"), Model(3, "b"), Model(4, "d"))

        val rendered = DefaultQueryRenderers().render(values, QueryRendererResult(values), context)

        assertEquals(listOf(Model(3, "b"), Model(1, "a")), rendered.data)
        assertEquals(listOf(1L, 2L, 4L), listOf(rendered.paging.page.toLong(), rendered.paging.size.toLong(), rendered.paging.totalItems))
    }

    @Test
    fun `query page rendering keeps provider owned paging`() = runBlocking {
        val page = QueryPage(listOf(Model(1, "one")), 2, 10, 21)
        val result = pipeline(performer(page)).perform(request, options())

        assertEquals(page.items, result.data)
        assertEquals(listOf(2L, 10L, 21L), listOf(result.paging.page.toLong(), result.paging.size.toLong(), result.paging.totalItems))
    }

    @Test
    fun `matching renderers and typed interceptors run in stable order for one shot values`() = runBlocking {
        val rendererOrder = mutableListOf<Int>()
        val interceptorOrder = mutableListOf<Int>()
        val renderers = DefaultQueryRenderers(listOf(
            modelRenderer(2, rendererOrder),
            modelRenderer(1, rendererOrder)
        ))
        val interceptors = DefaultReadModelInterceptors(listOf(
            modelInterceptor(2, interceptorOrder),
            modelInterceptor(1, interceptorOrder)
        ))
        val result = pipeline(performer(Model(1, "value")), renderers, interceptors).perform(request, options())

        assertEquals(listOf(1, 2), rendererOrder)
        assertEquals(listOf(1, 2), interceptorOrder)
        assertEquals("value-r1-r2-i1-i2", (result.data as Model).value)
    }

    @Test
    fun `typed interceptors run for every item of every observable emission`() = runBlocking {
        val intercepted = mutableListOf<Int>()
        val pipeline = observablePipeline(
            flowOf(listOf(Model(1, "one")), listOf(Model(2, "two"))),
            interceptors = DefaultReadModelInterceptors(listOf(modelInterceptor(0, intercepted)))
        )

        val opened = pipeline.open(request, options()) as ObservableQueryOpenResult.Stream
        val emissions = opened.results.toList()

        assertEquals(listOf(0, 0), intercepted)
        assertEquals(listOf(Model(1, "one-i0")), emissions[0].data)
        assertEquals(listOf(Model(2, "two-i0")), emissions[1].data)
    }

    @Test
    fun `declared command read model resolver wins fallback and equal ownership conflicts`() = runBlocking {
        val fallback = resolver(ReadModelForCommandOwnership.FALLBACK, Model(1, "fallback"))
        val declared = resolver(ReadModelForCommandOwnership.DECLARED, Model(1, "declared"))
        val registry = ReadModelForCommandResolverRegistry(listOf(fallback, declared))
        val context = commandContext(registry, commandKey = 42)

        assertEquals(Model(1, "declared"), registry.resolve(Model::class.java, context))
        assertThrows(MultipleReadModelResolversForCommandException::class.java) {
            ReadModelForCommandResolverRegistry(listOf(declared, resolver(ReadModelForCommandOwnership.DECLARED, null)))
        }
        assertThrows(MultipleReadModelResolversForCommandException::class.java) {
            ReadModelForCommandResolverRegistry(listOf(fallback, resolver(ReadModelForCommandOwnership.FALLBACK, null)))
        }
    }

    @Test
    fun `command argument resolver gives registry-owned read models precedence over ordinary services`() = runBlocking {
        val resolved = Model(42, "resolved")
        val serviceValue = Model(42, "service")
        val registry = ReadModelForCommandResolverRegistry(listOf(resolver(ReadModelForCommandOwnership.DECLARED, resolved)))
        val context = commandContext(registry, commandKey = 42, serviceValue = serviceValue)

        val model = CommandHandlerArgumentResolver(context).resolve(Model::class.java, "handle", "current")

        assertSame(resolved, model)
    }

    @Test
    fun `optional command argument resolves an ordinary service when no read model owner claims it`() = runBlocking {
        val serviceValue = Model(42, "service")
        val context = commandContext(ReadModelForCommandResolverRegistry(emptyList()), null, serviceValue)

        val resolved = CommandHandlerArgumentResolver(context).resolveOptional(Model::class.java, "handle", "dependency")

        assertSame(serviceValue, resolved.orElseThrow())
    }

    @Test
    fun `optional command argument missing service names the underlying dependency`() {
        val context = commandContext(ReadModelForCommandResolverRegistry(emptyList()), null)

        val exception = assertThrows(CannotResolveCommandDependency::class.java) {
            runBlocking {
                CommandHandlerArgumentResolver(context).resolveOptional(Model::class.java, "handle", "dependency")
            }
        }

        assertSame(Model::class.java, exception.dependencyType)
        assertTrue(Model::class.java.name in exception.message.orEmpty())
        assertFalse(java.util.Optional::class.java.name in exception.message.orEmpty())
    }

    @Test
    fun `provided value wins over read model owner and ordinary service for an optional command argument`() = runBlocking {
        val provided = Model(42, "provided")
        val serviceValue = Model(42, "service")
        val ownedValue = Model(42, "owned")
        val context = commandContext(
            ReadModelForCommandResolverRegistry(
                listOf(resolver(ReadModelForCommandOwnership.DECLARED, ownedValue))
            ),
            42,
            serviceValue,
            listOf(provided)
        )

        val resolved = CommandHandlerArgumentResolver(context).resolveOptional(Model::class.java, "handle", "dependency")

        assertSame(provided, resolved.orElseThrow())
    }

    @Test
    fun `command read model resolution fails clearly without a command key`() {
        val registry = ReadModelForCommandResolverRegistry(
            listOf(resolver(ReadModelForCommandOwnership.DECLARED, Model(1, "unused")))
        )
        val exception = assertThrows(java.util.concurrent.CompletionException::class.java) {
            registry.resolveBlocking(Model::class.java, commandContext(registry, commandKey = null))
        }
        assertTrue(exception.cause is UnableToResolveReadModelFromCommandContext)
    }

    @Test
    fun `health tracker publishes connection query and timestamp snapshots`() {
        var now = Instant.parse("2025-01-01T00:00:00Z")
        val tracker = DefaultQueryHealthTracker { now }
        val metadata = QuerySubscriptionMetadata(
            "subscription-one",
            queryName.value,
            Model::class.java.name,
            now,
            QuerySubscriptionClientInfo("127.0.0.1", "tests", "user-42", "websocket")
        )
        tracker.registerSubscription("connection-one", "websocket", metadata)
        now = now.plusSeconds(1)
        tracker.recordPingSent("connection-one")
        now = now.plusSeconds(1)
        tracker.recordPongReceived("connection-one", "subscription-one")
        now = now.plusSeconds(1)
        tracker.recordDataServed("connection-one", "subscription-one")

        val health = tracker.snapshot()

        assertEquals(1, health.totalConnections)
        assertEquals(1, health.totalSubscriptions)
        assertEquals(queryName.value, health.querySubscriptions.single().queryName)
        assertEquals(now, health.connections.single().subscriptions.single().lastDataServedAt)
        tracker.unregisterSubscription("connection-one", "subscription-one")
        assertEquals(0, tracker.snapshot().totalConnections)
    }

    @Test
    fun `emission guards receive captured caller context and deny terminates with unauthorized result`() = runBlocking {
        val calls = mutableListOf<ObservableQueryEmissionContext>()
        val guards = DefaultObservableQueryEmissionGuards(listOf(GuardObservableQueryEmission { context ->
            calls.add(context)
            CompletableFuture.completedFuture(
                if (context.isFirstEmission) ObservableQueryEmissionVerdict.ALLOW
                else ObservableQueryEmissionVerdict.DENY_AND_TERMINATE
            )
        }))
        val pipeline = observablePipeline(flowOf(Model(1, "one"), Model(2, "two")), guards = guards)

        val opened = pipeline.open(request, options()) as ObservableQueryOpenResult.Stream
        val emissions = opened.results.toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions.first().isAuthorized)
        assertFalse(emissions.last().isAuthorized)
        assertEquals(listOf(true, false), calls.map { it.isFirstEmission })
        assertTrue(calls.all { it.principal === principal })
        assertTrue(calls.all { it.tenantId == "tenant-one" && it.tenantNamespace == "tenant-namespace" })
    }

    @Test
    fun `emission guard failures fail closed`() = runBlocking {
        val guards = DefaultObservableQueryEmissionGuards(listOf(GuardObservableQueryEmission {
            CompletableFuture.failedFuture(IllegalStateException("guard failed"))
        }))
        val opened = observablePipeline(flowOf(Model(1, "one")), guards = guards)
            .open(request, options()) as ObservableQueryOpenResult.Stream

        val emissions = opened.results.toList()

        assertEquals(1, emissions.size)
        assertFalse(emissions.single().isAuthorized)
    }

    private fun pipeline(
        performer: QueryPerformer,
        renderers: QueryRenderers = DefaultQueryRenderers(),
        interceptors: ReadModelInterceptors = DefaultReadModelInterceptors()
    ): DefaultQueryPipeline {
        val registry = ConcurrentQueryPerformerRegistry().also { it.register(performer) }
        return DefaultQueryPipeline(registry, renderers = renderers, readModelInterceptors = interceptors)
    }

    private fun observablePipeline(
        flow: kotlinx.coroutines.flow.Flow<*>,
        interceptors: ReadModelInterceptors = DefaultReadModelInterceptors(),
        guards: ObservableQueryEmissionGuards = DefaultObservableQueryEmissionGuards()
    ): DefaultObservableQueryPipeline {
        val registry = ConcurrentQueryPerformerRegistry().also { it.register(performer(flow, QueryTransportType.OBSERVABLE)) }
        return DefaultObservableQueryPipeline(registry, readModelInterceptors = interceptors, emissionGuards = guards)
    }

    private fun performer(value: Any?, transport: QueryTransportType = QueryTransportType.REQUEST_RESPONSE): QueryPerformer =
        object : QueryPerformer {
            override val fullyQualifiedName = queryName
            override val descriptor = QueryDescriptor("all", "Tests.Models", Model::class.java.name, transport = transport)
            override suspend fun perform(context: QueryContext): Any? = value
        }

    private fun modelRenderer(order: Int, calls: MutableList<Int>): BlockingQueryRendererFor<Model> =
        object : BlockingQueryRendererFor<Model> {
            override fun queryType(): Class<Model> = Model::class.java
            override fun order(): Int = order
            override fun renderBlocking(query: Model, current: QueryRendererResult, context: QueryContext): QueryRendererResult {
                calls.add(order)
                val model = current.data as Model
                return QueryRendererResult(model.copy(value = "${model.value}-r$order"), current.paging)
            }
        }

    private fun modelInterceptor(order: Int, calls: MutableList<Int>): BlockingReadModelInterceptor<Model> =
        object : BlockingReadModelInterceptor<Model> {
            override fun readModelType(): Class<Model> = Model::class.java
            override fun order(): Int = order
            override fun interceptBlocking(readModel: Model, context: QueryContext): Model {
                calls.add(order)
                return readModel.copy(value = "${readModel.value}-i$order")
            }
        }

    private fun resolver(ownership: ReadModelForCommandOwnership, value: Model?): CanResolveReadModelForCommand =
        object : BlockingReadModelForCommandResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(Model::class.java)
            override fun ownership(): ReadModelForCommandOwnership = ownership
            override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? = value
        }

    private fun queryContext(queryRequest: QueryRequest = request): QueryContext = QueryContext(
        UUID.randomUUID(),
        queryRequest,
        queryName,
        principal,
        "tenant-one",
        "tenant-namespace",
        EmptyServices,
        null,
        true
    )

    private fun options(): QueryExecutionOptions = QueryExecutionOptions(
        UUID.randomUUID(),
        principal,
        EmptyServices,
        "tenant-one",
        "tenant-namespace",
        exposeExceptionDetails = true
    )

    private fun commandContext(
        registry: ReadModelForCommandResolverRegistry,
        commandKey: Any?,
        serviceValue: Model? = null,
        providedValues: Collection<Any> = emptyList()
    ): CommandContext {
        val resolver = object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = when (type) {
                ReadModelForCommandResolverRegistry::class.java -> type.cast(registry)
                Model::class.java -> serviceValue?.let(type::cast)
                else -> null
            }
        }
        return CommandContext(
            UUID.randomUUID(),
            TestCommand,
            TestCommand::class.java,
            principal,
            serviceResolver = resolver,
            providedValues = providedValues,
            commandKey = commandKey
        )
    }

    private data class Model(val id: Int, val value: String)
    private data object TestCommand

    private data object EmptyServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
