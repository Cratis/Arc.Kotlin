// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.results.PagingInfo
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryPipelineTest {
    private val correlationId = UUID.fromString("da4ac350-cf54-4ced-8da2-0399ed40b9cc")
    private val name = FullyQualifiedQueryName("io.cratis.Orders.all")
    private val principal = ArcPrincipal("Ada", true, setOf("operator"))
    private val services = EmptyServiceResolver()
    private val request = QueryRequest(name)
    private val options = QueryExecutionOptions(
        correlationId,
        principal,
        services,
        "tenant-one",
        "tenant-one-namespace",
        exposeExceptionDetails = true
    )

    @Test
    fun `single model is returned as single data`() = runBlocking {
        val model = Order("one")
        val result = pipeline(TestPerformer(name) { model }).perform(request, options)

        assertSame(model, result.data)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `list and array results are returned as immutable list data`() = runBlocking {
        val listResult = pipeline(TestPerformer(name) { mutableListOf(Order("one")) }).perform(request, options)
        val arrayResult = pipeline(TestPerformer(name) { arrayOf(Order("one"), Order("two")) }).perform(request, options)

        assertEquals(listOf("one"), (listResult.data as List<*>).map { (it as Order).id })
        assertEquals(listOf("one", "two"), (arrayResult.data as List<*>).map { (it as Order).id })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (listResult.data as MutableList<Any?>).add(Order("two"))
        }
    }

    @Test
    fun `query page maps items and response paging fields`() = runBlocking {
        val page = QueryPage(listOf(Order("one"), Order("two")), 2, 10, 21)
        val result = pipeline(TestPerformer(name) { page }).perform(request, options)

        assertEquals(page.items, result.data)
        assertEquals(2, result.paging.page)
        assertEquals(10, result.paging.size)
        assertEquals(21, result.paging.totalItems)
        assertEquals(3, result.paging.totalPages)
    }

    @Test
    fun `null performer data is a successful null result`() = runBlocking {
        val result = pipeline(TestPerformer(name) { null }).perform(request, options)

        assertTrue(result.isSuccess)
        assertNull(result.data)
    }

    @Test
    fun `direct result keeps control state and paging but uses request correlation`() = runBlocking {
        val direct = QueryResult(
            correlationId = UUID.randomUUID(),
            data = listOf(Order("one")),
            validationResults = listOf(validation(ValidationResultSeverity.Warning, "warning")),
            paging = PagingInfo(3, 5, 27)
        )
        val severityOptions = QueryExecutionOptions(
            correlationId,
            principal,
            services,
            allowedValidationSeverity = ValidationResultSeverity.Unknown
        )
        val result = pipeline(TestPerformer(name) { direct }).perform(request, severityOptions)

        assertEquals(correlationId, result.correlationId)
        assertEquals(direct.data, result.data)
        assertEquals(listOf("warning"), result.validationResults.map { it.message })
        assertEquals(3, result.paging.page)
        assertEquals(5, result.paging.size)
        assertEquals(27, result.paging.totalItems)
    }

    @Test
    fun `missing performer is an exception result and does not run filters`() = runBlocking {
        var filterRan = false
        val result = DefaultQueryPipeline(
            ConcurrentQueryPerformerRegistry(),
            listOf(QueryFilter {
                filterRan = true
                QueryResult.success<Any?>(it.correlationId)
            })
        ).perform(request, options)

        assertFalse(result.isSuccess)
        assertFalse(filterRan)
        assertEquals(listOf("No performer found for query $name"), result.exceptionMessages)
    }

    @Test
    fun `authorization filters run first with stable order`() = runBlocking {
        val order = mutableListOf<String>()
        val filters = listOf(
            namedFilter("ordinary-one", order),
            namedAuthorizationFilter("authorization-one", order),
            namedFilter("ordinary-two", order),
            namedAuthorizationFilter("authorization-two", order)
        )

        pipeline(TestPerformer(name), filters).perform(request, options)

        assertEquals(
            listOf("authorization-one", "authorization-two", "ordinary-one", "ordinary-two"),
            order
        )
    }

    @Test
    fun `first blocking filter short circuits performer and remaining filters`() = runBlocking {
        val order = mutableListOf<String>()
        val performer = TestPerformer(name)
        val filters = listOf(
            QueryFilter {
                order.add("first")
                QueryResult.invalid<Any?>(it.correlationId, listOf(validation(ValidationResultSeverity.Error, "blocked")))
            },
            namedFilter("second", order)
        )

        val result = pipeline(performer, filters).perform(request, options)

        assertEquals(listOf("first"), order)
        assertEquals(0, performer.invocations)
        assertEquals(listOf("blocked"), result.validationResults.map { it.message })
    }

    @Test
    fun `ordinary filter and performer exceptions become results`() = runBlocking {
        val filterFailure = pipeline(
            TestPerformer(name),
            listOf(QueryFilter { throw IllegalStateException("filter failed") })
        ).perform(request, options)
        val performerFailure = pipeline(
            TestPerformer(name) { throw IllegalArgumentException("performer failed") }
        ).perform(request, options)

        assertEquals(listOf("filter failed"), filterFailure.exceptionMessages)
        assertTrue(filterFailure.exceptionStackTrace.contains("IllegalStateException: filter failed"))
        assertEquals(listOf("performer failed"), performerFailure.exceptionMessages)
        assertTrue(performerFailure.exceptionStackTrace.contains("IllegalArgumentException: performer failed"))
    }

    @Test
    fun `validation severity uses exact strict numeric thresholds`() = runBlocking {
        val validations = ValidationResultSeverity.entries.map { validation(it, it.name) }
        val filter = QueryFilter { QueryResult.invalid<Any?>(it.correlationId, validations) }

        suspend fun messages(allowed: ValidationResultSeverity?): List<String> {
            val severityOptions = QueryExecutionOptions(
                correlationId,
                principal,
                services,
                allowedValidationSeverity = allowed
            )
            return pipeline(TestPerformer(name), listOf(filter)).perform(request, severityOptions)
                .validationResults.map { it.message }
        }

        assertEquals(listOf("Error"), messages(null))
        assertEquals(listOf("Information", "Warning", "Error"), messages(ValidationResultSeverity.Unknown))
        assertEquals(listOf("Warning", "Error"), messages(ValidationResultSeverity.Information))
        assertEquals(listOf("Error"), messages(ValidationResultSeverity.Warning))
        assertEquals(emptyList<String>(), messages(ValidationResultSeverity.Error))
    }

    @Test
    fun `cancellation from filters and performers is rethrown`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                pipeline(TestPerformer(name), listOf(QueryFilter { throw CancellationException("filter") }))
                    .perform(request, options)
            }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                pipeline(TestPerformer(name) { throw CancellationException("performer") })
                    .perform(request, options)
            }
        }
    }

    @Test
    fun `async bridge cancellation cancels the caller scope operation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val performer = TestPerformer(name) {
            try {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val future = AsyncQueryPipeline(pipeline(performer), scope).perform(request, options).toCompletableFuture()

        started.await()
        future.cancel(true)
        withTimeout(1_000) { cancelled.await() }

        assertTrue(future.isCancelled)
        scope.cancel()
    }

    @Test
    fun `context carries every explicit host value and immutable request`() = runBlocking {
        var received: QueryContext? = null
        val arguments = linkedMapOf<String, Any?>("state" to "open")
        val sorting = QuerySorting("createdAt", QuerySortDirection.DESCENDING)
        val explicitRequest = QueryRequest(name, arguments, QueryPaging(2, 25), sorting)
        arguments["state"] = "changed"

        pipeline(TestPerformer(name) { context ->
            received = context
            null
        }).perform(explicitRequest, options)

        val context = requireNotNull(received)
        assertEquals(correlationId, context.correlationId)
        assertSame(explicitRequest, context.request)
        assertEquals(name, context.queryName)
        assertEquals("open", context.request.arguments["state"])
        assertSame(sorting, context.request.sorting)
        assertEquals(principal, context.principal)
        assertEquals("tenant-one", context.tenantId)
        assertEquals("tenant-one-namespace", context.tenantNamespace)
        assertSame(services, context.serviceResolver)
        assertTrue(context.exposeExceptionDetails)
    }

    @Test
    fun `registry rejects exact duplicate names and snapshots deterministically`() {
        val registry = ConcurrentQueryPerformerRegistry()
        val z = TestPerformer(FullyQualifiedQueryName("z.query"))
        val a = TestPerformer(FullyQualifiedQueryName("a.query"))
        registry.register(z)
        registry.register(a)

        assertEquals(listOf("a.query", "z.query"), registry.snapshot().map { it.fullyQualifiedName.value })
        assertSame(a, registry.find(FullyQualifiedQueryName("a.query")))
        val exception = assertThrows(DuplicateQueryPerformerException::class.java) { registry.register(a) }
        assertEquals(a.fullyQualifiedName, exception.queryName)
    }

    @Test
    fun `request pageSize and response size retain distinct wire fields`() {
        val mapper = ArcObjectMapper.create()
        val requestTree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            QueryRequest(
                name,
                paging = QueryPaging(2, 25),
                sorting = QuerySorting("createdAt", QuerySortDirection.DESCENDING)
            )
        )
        val resultTree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            QueryResult.success<List<Order>>(correlationId, emptyList(), PagingInfo(2, 25, 51))
        )

        assertEquals(name.value, requestTree["queryName"].textValue())
        assertEquals(25, requestTree["paging"]["pageSize"].intValue())
        assertNull(requestTree["paging"]["size"])
        assertEquals(2, requestTree["sorting"]["direction"].intValue())
        assertEquals(25, resultTree["paging"]["size"].intValue())
        assertNull(resultTree["paging"]["pageSize"])
    }

    private fun pipeline(
        performer: QueryPerformer,
        filters: List<QueryFilter> = emptyList()
    ): DefaultQueryPipeline {
        val registry = ConcurrentQueryPerformerRegistry()
        registry.register(performer)
        return DefaultQueryPipeline(registry, filters)
    }

    private fun namedFilter(name: String, order: MutableList<String>): QueryFilter = QueryFilter {
        order.add(name)
        QueryResult.success<Any?>(it.correlationId)
    }

    private fun namedAuthorizationFilter(name: String, order: MutableList<String>): AuthorizationQueryFilter =
        object : AuthorizationQueryFilter {
            override suspend fun execute(context: QueryContext): QueryResult<*> {
                order.add(name)
                return QueryResult.success<Any?>(context.correlationId)
            }
        }

    private fun validation(severity: ValidationResultSeverity, message: String): ValidationResult =
        ValidationResult(severity, message)

    private data class Order(val id: String)

    private class EmptyServiceResolver : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }

    private class TestPerformer(
        override val fullyQualifiedName: FullyQualifiedQueryName,
        private val operation: suspend (QueryContext) -> Any? = { null }
    ) : QueryPerformer {
        override val descriptor = QueryDescriptor("all", "io.cratis.Orders", "java.lang.Object")
        override val allowsAnonymous = false
        override val supportsPaging = false
        var invocations = 0
            private set

        override suspend fun perform(context: QueryContext): Any? {
            invocations++
            return operation(context)
        }
    }
}
