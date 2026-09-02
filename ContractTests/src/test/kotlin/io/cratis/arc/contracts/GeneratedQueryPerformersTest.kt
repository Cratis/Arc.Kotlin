// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactModuleRegistry
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.contracts.fixtures.JavaMapReadModel
import io.cratis.arc.contracts.fixtures.JavaQueryDependency
import io.cratis.arc.contracts.fixtures.JavaQueryReadModel
import io.cratis.arc.contracts.fixtures.JavaTemporalReadModel
import io.cratis.arc.contracts.fixtures.KotlinMapReadModel
import io.cratis.arc.contracts.fixtures.KotlinQueryDependency
import io.cratis.arc.contracts.fixtures.KotlinQueryReadModel
import io.cratis.arc.contracts.fixtures.KotlinTemporalReadModel
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.queries.QueryTransportType
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedQueryPerformersTest {
    @Test
    fun `generated module contains deterministically sorted query performers`() {
        val performers = ContractTestsArcArtifactModule().queryPerformers

        assertEquals(
            listOf(
                "io.cratis.arc.contracts.fixtures.ConceptTemporalReadModel.findConceptTemporal",
                "io.cratis.arc.contracts.fixtures.JavaMapReadModel.getJavaMap",
                "io.cratis.arc.contracts.fixtures.JavaQueryReadModel.byId",
                "io.cratis.arc.contracts.fixtures.JavaQueryReadModel.contextualJava",
                "io.cratis.arc.contracts.fixtures.JavaQueryReadModel.observeJava",
                "io.cratis.arc.contracts.fixtures.JavaQueryReadModel.springDataAsync",
                "io.cratis.arc.contracts.fixtures.JavaQueryReadModel.springDataJavaDirect",
                "io.cratis.arc.contracts.fixtures.JavaTemporalReadModel.findJavaTemporal",
                "io.cratis.arc.contracts.fixtures.KotlinMapReadModel.getKotlinMap",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.all",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.contextualKotlin",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.defaulted",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.defaultedSuspend",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.filtered",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.observeAll",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.observeDefaulted",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.observeSingle",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.optional",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.page",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.single",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.springDataDirect",
                "io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.springDataSuspend",
                "io.cratis.arc.contracts.fixtures.KotlinTemporalReadModel.findKotlinTemporal"
            ),
            performers.map { performer -> performer.fullyQualifiedName.value }
        )
    }

    @Test
    fun `generated Kotlin and Java map read model queries execute through the real pipeline`() = runBlocking {
        val pipeline = createPipeline()

        val kotlinResult = pipeline.perform(request("KotlinMapReadModel.getKotlinMap"), options(emptyMap()))
        val javaResult = pipeline.perform(request("JavaMapReadModel.getJavaMap"), options(emptyMap()))

        assertEquals(
            KotlinMapReadModel(
                mapOf("language" to "kotlin"),
                mapOf("values" to listOf(1, 2)),
                mapOf("flags" to mapOf("ready" to true)),
                null
            ),
            kotlinResult.data
        )
        assertEquals(JavaMapReadModel.getJavaMap(), javaResult.data)
        assertTrue(kotlinResult.isSuccess)
        assertTrue(javaResult.isSuccess)
    }

    @Test
    fun `Kotlin single list nullable and page queries execute through the real pipeline`() = runBlocking {
        val dependency = KotlinQueryDependency()
        val pipeline = createPipeline()
        val executionOptions = options(mapOf(KotlinQueryDependency::class.java to dependency))

        val single = pipeline.perform(request("KotlinQueryReadModel.single", "identifier" to "model-one"), executionOptions)
        val all = pipeline.perform(request("KotlinQueryReadModel.all", "prefix" to "group"), executionOptions)
        val optional = pipeline.perform(request("KotlinQueryReadModel.optional"), executionOptions)
        val page = pipeline.perform(request("KotlinQueryReadModel.page", "pageNumber" to 2), executionOptions)

        assertEquals(KotlinQueryReadModel("model-one"), single.data)
        assertEquals(listOf(KotlinQueryReadModel("group-one"), KotlinQueryReadModel("group-two")), all.data)
        assertEquals(1, dependency.invocationCount)
        assertEquals(listOf(KotlinQueryReadModel("none")), optional.data)
        assertEquals(listOf(KotlinQueryReadModel("page-2")), page.data)
        assertEquals(2, page.paging.page)
        assertEquals(1, page.paging.size)
        assertEquals(3, page.paging.totalItems)
        assertTrue(listOf(single, all, optional, page).all { result -> result.isSuccess })
    }

    @Test
    fun `Spring Data direct suspend and CompletionStage queries preserve provider pages without repaging`() = runBlocking {
        val kotlinDependency = KotlinQueryDependency()
        val javaDependency = JavaQueryDependency()
        val pipeline = createPipeline()
        val executionOptions = options(
            mapOf(
                KotlinQueryDependency::class.java to kotlinDependency,
                JavaQueryDependency::class.java to javaDependency
            )
        )

        val kotlinDirect = pipeline.perform(
            springRequest(
                "KotlinQueryReadModel.springDataDirect",
                mapOf("label" to "kotlin-direct"),
                page = 2,
                pageSize = 5,
                sortBy = "value",
                sortDirection = QuerySortDirection.DESCENDING
            ),
            executionOptions
        )
        val kotlinSuspend = pipeline.perform(
            springRequest(
                "KotlinQueryReadModel.springDataSuspend",
                mapOf("label" to "kotlin-suspend"),
                page = 0,
                pageSize = 0,
                sortBy = "value"
            ),
            executionOptions
        )
        val javaDirect = pipeline.perform(
            springRequest(
                "JavaQueryReadModel.springDataJavaDirect",
                mapOf("label" to "java-direct"),
                page = 1,
                pageSize = 3
            ),
            executionOptions
        )
        val javaAsync = pipeline.perform(
            springRequest(
                "JavaQueryReadModel.springDataAsync",
                mapOf("label" to "java-async"),
                page = 3,
                pageSize = 4,
                sortBy = "value"
            ),
            executionOptions
        )

        assertEquals(listOf(KotlinQueryReadModel("kotlin-direct|2:5|DESC")), kotlinDirect.data)
        assertEquals(listOf(KotlinQueryReadModel("kotlin-suspend|unpaged|ASC")), kotlinSuspend.data)
        assertEquals(listOf(JavaQueryReadModel("java-direct|1:3|UNSORTED")), javaDirect.data)
        assertEquals(listOf(JavaQueryReadModel("java-async|3:4|ASC")), javaAsync.data)
        assertEquals(listOf(2, 0, 1, 3), listOf(kotlinDirect, kotlinSuspend, javaDirect, javaAsync).map { it.paging.page })
        assertEquals(listOf(5, 0, 3, 4), listOf(kotlinDirect, kotlinSuspend, javaDirect, javaAsync).map { it.paging.size })
        assertEquals(listOf(37L, 53L, 61L, 79L), listOf(kotlinDirect, kotlinSuspend, javaDirect, javaAsync).map { it.paging.totalItems })
        assertTrue(listOf(kotlinDirect, kotlinSuspend, javaDirect, javaAsync).all { result -> result.isSuccess })
        assertEquals(2, kotlinDependency.invocationCount)
        assertEquals(2, javaDependency.invocationCount)
    }

    @Test
    fun `Kotlin defaults distinguish omission from present typed values for direct and suspending queries`() = runBlocking {
        val dependency = KotlinQueryDependency()
        val pipeline = createPipeline()
        val executionOptions = options(mapOf(KotlinQueryDependency::class.java to dependency))

        val absent = pipeline.perform(
            request("KotlinQueryReadModel.defaulted", "required" to "root"),
            executionOptions
        )
        val dependent = pipeline.perform(
            request("KotlinQueryReadModel.defaulted", "required" to "root", "prefix" to "custom"),
            executionOptions
        )
        val present = pipeline.perform(
            request(
                "KotlinQueryReadModel.defaulted",
                "required" to "root",
                "prefix" to "present",
                "suffix" to "override",
                "count" to 7
            ),
            executionOptions
        )
        val malformed = pipeline.perform(
            request("KotlinQueryReadModel.defaulted", "required" to "root", "count" to "seven"),
            executionOptions
        )
        val suspendAbsent = pipeline.perform(request("KotlinQueryReadModel.defaultedSuspend"), executionOptions)
        val suspendPresent = pipeline.perform(
            request("KotlinQueryReadModel.defaultedSuspend", "label" to "provided"),
            executionOptions
        )

        assertEquals(KotlinQueryReadModel("root|default|default-suffix|2"), absent.data)
        assertEquals(KotlinQueryReadModel("root|custom|custom-suffix|2"), dependent.data)
        assertEquals(KotlinQueryReadModel("root|present|override|7"), present.data)
        assertEquals(listOf(KotlinQueryReadModel("suspend-default-one"), KotlinQueryReadModel("suspend-default-two")), suspendAbsent.data)
        assertEquals(listOf(KotlinQueryReadModel("provided-one"), KotlinQueryReadModel("provided-two")), suspendPresent.data)
        assertTrue(listOf(absent, dependent, present, suspendAbsent, suspendPresent).all { result -> result.isSuccess })
        assertFalse(malformed.isSuccess)
        assertEquals(
            listOf("Query argument 'count' must be of type 'kotlin.Int'."),
            malformed.validationResults.map { result -> result.message }
        )
        assertEquals(5, dependency.invocationCount)
    }

    @Test
    fun `Kotlin Flow query default runs only when its client argument is omitted`() = runBlocking {
        val registry = ConcurrentQueryPerformerRegistry()
        ContractTestsArcArtifactModule().queryPerformers.forEach(registry::register)
        val pipeline = DefaultObservableQueryPipeline(registry)

        val absent = pipeline.open(request("KotlinQueryReadModel.observeDefaulted"), options())
        val present = pipeline.open(
            request("KotlinQueryReadModel.observeDefaulted", "label" to "provided-flow"),
            options()
        )

        assertTrue(absent is ObservableQueryOpenResult.Stream)
        assertTrue(present is ObservableQueryOpenResult.Stream)
        assertEquals(
            listOf(KotlinQueryReadModel("flow-default")),
            (absent as ObservableQueryOpenResult.Stream).results.first().data
        )
        assertEquals(
            listOf(KotlinQueryReadModel("provided-flow")),
            (present as ObservableQueryOpenResult.Stream).results.first().data
        )
    }

    @Test
    fun `Kotlin and Java direct queries receive exact request and context in declaration order`() = runBlocking {
        val correlationId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val pipeline = createPipeline()

        val kotlin = pipeline.perform(
            request("KotlinQueryReadModel.contextualKotlin", "label" to "kotlin"),
            options(correlationId = correlationId)
        )
        val java = pipeline.perform(
            request("JavaQueryReadModel.contextualJava", "label" to "java"),
            options(correlationId = correlationId)
        )

        assertEquals(KotlinQueryReadModel("kotlin-$correlationId"), kotlin.data)
        assertEquals(JavaQueryReadModel("java-$correlationId"), java.data)
        assertTrue(kotlin.isSuccess)
        assertTrue(java.isSuccess)
    }

    @Test
    fun `Java static CompletionStage query receives typed arguments infrastructure and service`() = runBlocking {
        val dependency = JavaQueryDependency()
        val pipeline = createPipeline()

        val result = pipeline.perform(
            QueryRequest(
                FullyQualifiedQueryName("io.cratis.arc.contracts.fixtures.JavaQueryReadModel.byId"),
                mapOf("identifier" to "seven")
            ),
            options(mapOf(JavaQueryDependency::class.java to dependency))
        )

        assertEquals(JavaQueryReadModel("java-seven"), result.data)
        assertEquals(1, dependency.invocationCount)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `Kotlin and Java temporal queries preserve typed parameters and models`() = runBlocking {
        val kotlinIdentifier = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val kotlinDate = LocalDate.of(2026, 8, 30)
        val kotlinTime = LocalTime.of(12, 30, 45)
        val javaIdentifier = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val javaDate = LocalDate.of(2026, 8, 31)
        val javaTime = LocalTime.of(13, 45, 15)
        val pipeline = createPipeline()

        val kotlinResult = pipeline.perform(
            request(
                "KotlinTemporalReadModel.findKotlinTemporal",
                "identifier" to kotlinIdentifier,
                "date" to kotlinDate,
                "time" to kotlinTime
            ),
            options()
        )
        val javaResult = pipeline.perform(
            request(
                "JavaTemporalReadModel.findJavaTemporal",
                "identifier" to javaIdentifier,
                "date" to javaDate,
                "time" to javaTime
            ),
            options()
        )

        assertTrue(kotlinResult.isSuccess)
        assertEquals(KotlinTemporalReadModel(kotlinIdentifier, kotlinDate, kotlinTime), kotlinResult.data)
        assertTrue(javaResult.isSuccess)
        assertEquals(JavaTemporalReadModel(javaIdentifier, javaDate, javaTime), javaResult.data)
    }

    @Test
    fun `Kotlin Flow and Java Publisher queries retain their captured subscription context`() = runBlocking {
        val registry = ConcurrentQueryPerformerRegistry()
        ContractTestsArcArtifactModule().queryPerformers.forEach(registry::register)
        val pipeline = DefaultObservableQueryPipeline(registry)
        val correlationId = UUID.fromString("44444444-4444-4444-4444-444444444444")

        val kotlinDependency = KotlinQueryDependency()
        val javaDependency = JavaQueryDependency()
        for ((query, expected) in listOf(
            "KotlinQueryReadModel.observeAll" to
                listOf(KotlinQueryReadModel("observable-kotlin-true-$correlationId")),
            "JavaQueryReadModel.observeJava" to
                listOf(JavaQueryReadModel("observable-java-java-true-$correlationId"))
        )) {
            val (label, services) = if (query.startsWith("Kotlin")) {
                "kotlin" to mapOf<Class<*>, Any>(KotlinQueryDependency::class.java to kotlinDependency)
            } else {
                "java" to mapOf<Class<*>, Any>(JavaQueryDependency::class.java to javaDependency)
            }
            val opened = pipeline.open(
                request(query, "label" to label),
                options(services, correlationId)
            )
            assertTrue(opened is ObservableQueryOpenResult.Stream)
            val result = (opened as ObservableQueryOpenResult.Stream).results.first()
            assertEquals(expected, result.data)
            val performer = requireNotNull(registry.find(request(query).queryName))
            assertEquals(QueryTransportType.OBSERVABLE, performer.descriptor.transport)
            assertTrue(performer.descriptor.isEnumerable)
        }
        assertEquals(1, kotlinDependency.invocationCount)
        assertEquals(1, javaDependency.invocationCount)
    }

    @Test
    fun `missing and wrong required arguments become deterministic validation failures`() = runBlocking {
        val pipeline = createPipeline()

        val missing = pipeline.perform(request("KotlinQueryReadModel.single"), options())
        val wrong = pipeline.perform(request("KotlinQueryReadModel.page", "pageNumber" to "two"), options())

        assertFalse(missing.isSuccess)
        assertEquals(listOf("Query argument 'identifier' is required."), missing.validationResults.map { it.message })
        assertEquals(listOf("identifier"), missing.validationResults.single().members)
        assertTrue(missing.exceptionMessages.isEmpty())
        assertFalse(wrong.isSuccess)
        assertEquals(
            listOf("Query argument 'pageNumber' must be of type 'kotlin.Int'."),
            wrong.validationResults.map { it.message }
        )
        assertEquals(listOf("pageNumber"), wrong.validationResults.single().members)
        assertTrue(wrong.exceptionMessages.isEmpty())
    }

    @Test
    fun `generated descriptors contain routes shapes parameters authorization and flags`() {
        val descriptors = ContractTestsArcArtifactModule().queryPerformers.associate {
            performer -> performer.fullyQualifiedName.value to performer.descriptor
        }
        val single = requireNotNull(descriptors["io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.single"])
        val all = requireNotNull(descriptors["io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.all"])
        val optional = requireNotNull(descriptors["io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.optional"])
        val page = requireNotNull(descriptors["io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.page"])
        val defaulted = requireNotNull(descriptors["io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.defaulted"])
        val java = requireNotNull(descriptors["io.cratis.arc.contracts.fixtures.JavaQueryReadModel.byId"])
        val javaClassDefault = requireNotNull(
            descriptors["io.cratis.arc.contracts.fixtures.JavaQueryReadModel.contextualJava"]
        )
        val kotlinObservable = requireNotNull(
            descriptors["io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.observeAll"]
        )
        val javaObservable = requireNotNull(
            descriptors["io.cratis.arc.contracts.fixtures.JavaQueryReadModel.observeJava"]
        )
        val springData = requireNotNull(
            descriptors["io.cratis.arc.contracts.fixtures.KotlinQueryReadModel.springDataDirect"]
        )

        assertEquals("single", single.name)
        assertEquals(KotlinQueryReadModel::class.java.name, single.declaringTypeName)
        assertEquals(KotlinQueryReadModel::class.java.name, single.returnTypeName)
        assertEquals("/kotlin-models/by-id", single.explicitPath)
        assertEquals("/kotlin-models/by-id", EndpointRouteHelper.queryRoute(single))
        assertEquals(QueryHttpMethodType.GET, single.queryHttpMethod)
        assertEquals(QueryHttpMethodType.QUERY, javaClassDefault.queryHttpMethod)
        assertEquals(QueryTransportType.REQUEST_RESPONSE, single.transport)
        assertEquals(listOf("io", "cratis", "arc", "contracts", "fixtures"), single.location)
        assertEquals(listOf("identifier"), single.parameters.map { parameter -> parameter.name })
        assertFalse(single.parameters.single().isNullable)
        assertFalse(single.parameters.single().isFromServices)

        assertTrue(all.isEnumerable)
        assertFalse(all.supportsPaging)
        assertEquals(
            listOf("prefix", "request", "dependency", "context"),
            all.parameters.map { parameter -> parameter.name }
        )
        assertEquals(
            listOf(
                QueryParameterSource.CLIENT,
                QueryParameterSource.QUERY_REQUEST,
                QueryParameterSource.SERVICE,
                QueryParameterSource.QUERY_CONTEXT
            ),
            all.parameters.map { parameter -> parameter.source }
        )
        assertEquals(listOf("prefix"), all.parameters.filter { it.source == QueryParameterSource.CLIENT }.map { it.name })
        assertTrue(all.parameters.single { it.name == "dependency" }.isFromServices)
        assertEquals("catalog", all.authorization.policy)
        assertEquals(listOf("viewer", "reader", "auditor"), all.authorization.roles)
        assertEquals(listOf("bearer"), all.authorization.schemes)
        assertFalse(all.authorization.allowAnonymous)
        assertTrue(all.treatWarningsAsErrors)

        assertTrue(optional.parameters.single().isNullable)
        assertTrue(optional.isEnumerable)
        assertEquals(
            listOf(false, true, false, true, false, true, false),
            defaulted.parameters.map { parameter -> parameter.hasDefault }
        )
        assertTrue(defaulted.parameters.filter { parameter -> parameter.hasDefault }.all { parameter ->
            parameter.source == QueryParameterSource.CLIENT
        })
        assertTrue(page.isEnumerable)
        assertTrue(page.supportsPaging)

        assertEquals("/java-models/by-id", java.explicitPath)
        assertEquals(QueryHttpMethodType.QUERY, java.queryHttpMethod)
        assertEquals(
            listOf(
                QueryParameterSource.QUERY_CONTEXT,
                QueryParameterSource.CLIENT,
                QueryParameterSource.SERVICE,
                QueryParameterSource.QUERY_REQUEST
            ),
            java.parameters.map { parameter -> parameter.source }
        )
        assertEquals(listOf("identifier"), java.parameters.filter { it.source == QueryParameterSource.CLIENT }.map { it.name })
        assertTrue(java.parameters.none { parameter -> parameter.hasDefault })
        assertTrue(java.authorization.allowAnonymous)
        assertFalse(java.isEnumerable)
        assertFalse(java.supportsPaging)
        assertNull(java.authorization.policy)

        assertEquals(
            listOf(
                QueryParameterSource.QUERY_CONTEXT,
                QueryParameterSource.CLIENT,
                QueryParameterSource.SERVICE,
                QueryParameterSource.QUERY_REQUEST
            ),
            kotlinObservable.parameters.map { parameter -> parameter.source }
        )
        assertEquals(
            listOf(
                QueryParameterSource.QUERY_REQUEST,
                QueryParameterSource.CLIENT,
                QueryParameterSource.SERVICE,
                QueryParameterSource.QUERY_CONTEXT
            ),
            javaObservable.parameters.map { parameter -> parameter.source }
        )

        assertEquals(
            listOf(
                QueryParameterSource.CLIENT,
                QueryParameterSource.HOST_ADAPTER,
                QueryParameterSource.SERVICE,
                QueryParameterSource.QUERY_REQUEST,
                QueryParameterSource.HOST_ADAPTER
            ),
            springData.parameters.map { parameter -> parameter.source }
        )
        assertEquals(listOf("label"), springData.parameters.filter { it.source == QueryParameterSource.CLIENT }.map { it.name })
        assertTrue(springData.isEnumerable)
        assertTrue(springData.supportsPaging)
        assertTrue(springData.supportsSorting)
    }

    private fun createPipeline(): DefaultQueryPipeline {
        val queryPerformers = ConcurrentQueryPerformerRegistry()
        ArcArtifactModuleRegistry.register(
            ContractTestsArcArtifactModule(),
            ConcurrentCommandHandlerRegistry(),
            queryPerformers
        )
        return DefaultQueryPipeline(queryPerformers)
    }

    private fun request(query: String, vararg arguments: Pair<String, Any?>): QueryRequest = QueryRequest(
        FullyQualifiedQueryName("io.cratis.arc.contracts.fixtures.$query"),
        mapOf(*arguments)
    )

    private fun springRequest(
        query: String,
        arguments: Map<String, Any?>,
        page: Int,
        pageSize: Int,
        sortBy: String = "",
        sortDirection: QuerySortDirection = QuerySortDirection.ASCENDING
    ): QueryRequest = QueryRequest(
        FullyQualifiedQueryName("io.cratis.arc.contracts.fixtures.$query"),
        arguments,
        QueryPaging(page, pageSize),
        QuerySorting(sortBy, sortDirection)
    )

    private fun options(
        services: Map<Class<*>, Any> = emptyMap(),
        correlationId: UUID = UUID.randomUUID()
    ): QueryExecutionOptions = QueryExecutionOptions(
        correlationId,
        ArcPrincipal.anonymous(),
        MapServiceResolver(services)
    )

    private class MapServiceResolver(private val services: Map<Class<*>, Any>) : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = services[type]?.let(type::cast)
    }
}
