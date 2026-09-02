// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.commands.require
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryPage
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.ChangeSet
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.tenancy.QueryStringTenantIdResolver
import io.cratis.arc.tenancy.TenantResolutionContext
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryScenarioTests {
    @Test
    fun `performs single list and page results through real pipeline with round trips`() = runBlocking {
        val singleModel = TestModel("single")
        val singlePerformer = ManualQueryPerformer { singleModel }
        val single = QueryScenario<TestModel>(
            ManualArtifactModule(performer = singlePerformer),
            ManualQueryPerformer.QUERY_NAME
        ).perform()
        val list = QueryScenario<List<TestModel>>(ManualQueryPerformer {
            listOf(TestModel("one"), TestModel("two"))
        }).perform()
        val page = QueryScenario<List<TestModel>>(ManualQueryPerformer {
            QueryPage(listOf(TestModel("page")), 2, 10, 21)
        }).perform(paging = QueryPaging(2, 10), sorting = QuerySorting("value", QuerySortDirection.DESCENDING))

        single.shouldBeReady().shouldSucceed().shouldHaveData(TestModel("single"))
        assertNotSame(singleModel, single.result.data)
        list.shouldHaveData(listOf(TestModel("one"), TestModel("two")))
        page.shouldHaveData(listOf(TestModel("page")))
        page.shouldHavePaging(2, 10, 21)
        Unit
    }

    @Test
    fun `query supports services context filters validators authorization and argument round trip`() = runBlocking {
        val dependency = TestDependency("service")
        val originalArgument = TestModel("argument")
        val performer = ManualQueryPerformer(
            authorization = AuthorizationMetadata(policy = "reader")
        ) { context ->
            val argument = context.request.arguments["model"] as TestModel
            assertNotSame(originalArgument, argument)
            TestModel(context.serviceResolver.require(TestDependency::class.java).prefix + ":" + argument.value)
        }
        val validator = object : QueryValidator {
            override val queryName = ManualQueryPerformer.QUERY_NAME
            override suspend fun validate(
                request: QueryRequest,
                context: io.cratis.arc.queries.QueryContext
            ): List<ValidationResult> = emptyList()
        }
        var filterInvoked = false
        val correlationId = UUID.randomUUID()
        val result = QueryScenario<TestModel>(performer)
            .addService(TestDependency::class.java, dependency)
            .addPolicy("reader") { AuthorizationResult.success() }
            .withPrincipal(ArcPrincipal("Ada", true))
            .withTenant("tenant", "namespace")
            .withCorrelationId(correlationId)
            .addValidator(validator)
            .addFilter(QueryFilter {
                filterInvoked = true
                QueryResult.success<Any?>(it.correlationId)
            })
            .perform(mapOf("model" to originalArgument))

        result.shouldSucceed().shouldHaveData(TestModel("service:argument"))
        assertTrue(filterInvoked)
        assertEquals(correlationId, result.result.correlationId)
        assertEquals("tenant", performer.lastContext?.tenantId)
        assertEquals("namespace", performer.lastContext?.tenantNamespace)
    }

    @Test
    fun `explicit tenant resolution context flows into query context`() = runBlocking {
        val performer = ManualQueryPerformer()

        QueryScenario<TestModel>(performer)
            .withTenantResolution(
                QueryStringTenantIdResolver(),
                TenantResolutionContext(query = mapOf("tenantId" to "resolved-tenant"))
            )
            .perform()
            .shouldSucceed()

        assertEquals("resolved-tenant", performer.lastContext?.tenantId)
        assertEquals("resolved-tenant", performer.lastContext?.tenantNamespace)
    }

    @Test
    fun `authorization and validation short circuit performer`() = runBlocking {
        val unauthorizedPerformer = ManualQueryPerformer(authorization = AuthorizationMetadata(policy = "reader"))
        val unauthorized = QueryScenario<TestModel>(unauthorizedPerformer)
            .addPolicy("reader") { AuthorizationResult.failure("reader required") }
            .withPrincipal(ArcPrincipal("Ada", true))
            .perform()

        unauthorized.shouldBeUnauthorized()
        assertEquals(0, unauthorizedPerformer.invocationCount)

        val invalidPerformer = ManualQueryPerformer()
        val invalid = QueryScenario<TestModel>(invalidPerformer)
            .addValidator(object : QueryValidator {
                override val queryName = ManualQueryPerformer.QUERY_NAME
                override suspend fun validate(
                    request: QueryRequest,
                    context: io.cratis.arc.queries.QueryContext
                ): List<ValidationResult> = listOf(
                    ValidationResult(
                        ValidationResultSeverity.Error,
                        "identifier is invalid",
                        listOf("identifier"),
                        reason = ValidationResultReasons.RULE
                    )
                )
            })
            .perform()

        invalid.shouldBeInvalid().shouldHaveValidation("identifier", "invalid", ValidationResultReasons.RULE)
        assertEquals(0, invalidPerformer.invocationCount)
    }

    @Test
    fun `result assertions cover data paging change sets and errors`() {
        val correlationId = UUID.randomUUID()
        val changes = ChangeSet(added = listOf(TestModel("added")))
        val success = QueryScenarioResult(
            QueryResult.success(correlationId, TestModel("data"), changeSet = changes)
        )
        assertEquals(1, success.shouldSucceed().shouldHaveChangeSet().added.size)
        success.shouldHaveNoErrors().shouldHavePaging(0, 0, 0)

        val failed = QueryScenarioResult(QueryResult.error<TestModel>(correlationId, "query exploded"))
        failed.shouldHaveErrors().shouldHaveExceptionMessage("exploded")

        val failure = assertThrows(AssertionError::class.java) { failed.shouldSucceed() }
        assertTrue(failure.message!!.contains("Expected the query to succeed"))
        assertTrue(failure.message!!.contains("query exploded"))
        assertTrue(failure.message!!.contains("QueryResult("))
    }

    @Test
    fun `missing and duplicate query artifacts fail setup clearly`() {
        val missing = ScenarioArtifactRegistry().register(ManualArtifactModule())
        val unknown = io.cratis.arc.queries.FullyQualifiedQueryName("missing.query")

        assertThrows(ScenarioSetupException::class.java) { missing.query(unknown) }
        assertThrows(ScenarioSetupException::class.java) {
            ScenarioArtifactRegistry()
                .register(ManualQueryPerformer())
                .register(ManualQueryPerformer())
        }
    }
}
