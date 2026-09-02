// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot

import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationRequestContext
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.identity.AsyncIdentityDetailsProvider
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat
import io.opentelemetry.api.baggage.Baggage
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.slf4j.MDC

internal class ArcObservedComponentsTests {
    @Test
    fun `command execute and validate record bounded outcomes and correlation context`() = runBlocking {
        val registry = TestObservationRegistry.create()
        val correlationId = UUID.randomUUID()
        var baggage: String? = null
        var logging: String? = null
        val delegate = object : CommandPipeline {
            override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> {
                baggage = Baggage.current().getEntryValue(ArcObservationTags.CORRELATION_ID)
                logging = MDC.get(ArcObservationTags.CORRELATION_ID)
                return CommandResult.success(options.correlationId)
            }

            override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> =
                CommandResult.invalid(
                    options.correlationId,
                    listOf(ValidationResult(ValidationResultSeverity.Error, "rejected by a filter"))
                )
        }
        val pipeline = ObservedCommandPipeline(delegate, recorder(registry))

        val options = CommandExecutionOptions(
            correlationId,
            ArcPrincipal("user-secret", true),
            NoServices,
            "tenant-secret",
            "tenant-namespace-secret"
        )
        pipeline.execute(TestCommand(), options)
        pipeline.validate(TestCommand(), options)

        assertEquals(correlationId.toString(), baggage)
        assertEquals(correlationId.toString(), logging)
        assertNull(MDC.get(ArcObservationTags.CORRELATION_ID))
        assertNull(Baggage.current().getEntryValue(ArcObservationTags.CORRELATION_ID))
        assertThat(registry).hasHandledContextsThatSatisfy { contexts ->
            val recordedValues = contexts.flatMap { context -> context.allKeyValues.map { keyValue -> keyValue.value } }
            assertFalse(recordedValues.any { it.contains("secret") || it == correlationId.toString() })
        }
        assertThat(registry)
            .hasNumberOfObservationsWithNameEqualTo(ArcObservationNames.COMMAND, 2)
            .hasAnObservationWithAKeyValue(ArcObservationTags.OPERATION, "execute")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OPERATION, "validate")
            .hasAnObservationWithAKeyValue(ArcObservationTags.ARTIFACT, TestCommand::class.java.name)
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "success")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "invalid")
    }

    @Test
    fun `thrown command errors and cancellation record terminal outcomes`() = runBlocking {
        val errorRegistry = TestObservationRegistry.create()
        val errorPipeline = ObservedCommandPipeline(ThrowingCommandPipeline(IllegalStateException("failed")), recorder(errorRegistry))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { errorPipeline.execute(TestCommand(), commandOptions()) }
        }
        assertThat(errorRegistry)
            .hasObservationWithNameEqualTo(ArcObservationNames.COMMAND)
            .that()
            .hasError()
            .hasLowCardinalityKeyValue(ArcObservationTags.OUTCOME, "error")

        val cancellationRegistry = TestObservationRegistry.create()
        val cancellationPipeline = ObservedCommandPipeline(
            ThrowingCommandPipeline(CancellationException("cancelled")),
            recorder(cancellationRegistry)
        )
        assertThrows(CancellationException::class.java) {
            runBlocking { cancellationPipeline.execute(TestCommand(), commandOptions()) }
        }
        assertThat(cancellationRegistry)
            .hasObservationWithNameEqualTo(ArcObservationNames.COMMAND)
            .that()
            .doesNotHaveError()
            .hasLowCardinalityKeyValue(ArcObservationTags.OUTCOME, "cancelled")
    }

    @Test
    fun `one shot and observable queries record perform open subscription and emissions`() = runBlocking {
        val registry = TestObservationRegistry.create()
        val request = queryRequest()
        val options = queryOptions()
        val queryPipeline = ObservedQueryPipeline(
            object : QueryPipeline {
                override suspend fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*> =
                    QueryResult.unauthorized<Any?>(options.correlationId)
            },
            recorder(registry)
        )
        queryPipeline.perform(request, options)

        val observable = ObservedObservableQueryPipeline(
            object : ObservableQueryPipeline {
                override suspend fun open(
                    request: QueryRequest,
                    options: QueryExecutionOptions,
                    transferMode: ObservableQueryTransferMode,
                    keyExtractor: ((Any) -> Any?)?
                ): ObservableQueryOpenResult = ObservableQueryOpenResult.Stream(
                    flowOf(
                        QueryResult.success(options.correlationId, "first"),
                        QueryResult.error<Any?>(options.correlationId, "failed emission")
                    )
                )
            },
            recorder(registry)
        )
        val opened = observable.open(request, options) as ObservableQueryOpenResult.Stream
        val received = mutableListOf<QueryResult<*>>()
        opened.results.collect(received::add)

        assertEquals(2, received.size)
        assertThat(registry)
            .hasObservationWithNameEqualTo(ArcObservationNames.QUERY)
            .that()
            .hasLowCardinalityKeyValue(ArcObservationTags.OUTCOME, "unauthorized")
            .backToTestObservationRegistry()
            .hasNumberOfObservationsWithNameEqualTo(ArcObservationNames.OBSERVABLE_QUERY, 4)
            .hasAnObservationWithAKeyValue(ArcObservationTags.OPERATION, "open")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OPERATION, "subscription")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OPERATION, "emission")
            .hasAnObservationWithAKeyValue(ArcObservationTags.QUERY, request.queryName.value)
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "opened")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "completed")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "error")
    }

    @Test
    fun `observable subscription cancellation is recorded`() = runBlocking {
        val registry = TestObservationRegistry.create()
        val observable = ObservedObservableQueryPipeline(
            object : ObservableQueryPipeline {
                override suspend fun open(
                    request: QueryRequest,
                    options: QueryExecutionOptions,
                    transferMode: ObservableQueryTransferMode,
                    keyExtractor: ((Any) -> Any?)?
                ): ObservableQueryOpenResult = ObservableQueryOpenResult.Stream(
                    flow<QueryResult<*>> { awaitCancellation() }
                )
            },
            recorder(registry)
        )
        val opened = observable.open(queryRequest(), queryOptions()) as ObservableQueryOpenResult.Stream

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(25) { opened.results.collect() } }
        }

        assertThat(registry)
            .hasAnObservationWithAKeyValue(ArcObservationTags.OPERATION, "subscription")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "cancelled")
    }

    @Test
    fun `authentication and coroutine and Java identity providers record outcomes`() = runBlocking {
        val registry = TestObservationRegistry.create()
        val authentication = ObservedAuthentication(
            object : Authentication {
                override val hasHandlers: Boolean = true
                override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult =
                    AuthenticationResult.ANONYMOUS
            },
            recorder(registry)
        )
        authentication.handleAuthentication(AuthenticationRequestContext())

        val identity = ObservedIdentityDetailsProvider(
            object : IdentityDetailsProvider<TestIdentity> {
                override val detailsType: Class<TestIdentity> = TestIdentity::class.java
                override suspend fun provide(context: IdentityProviderContext): IdentityDetails<TestIdentity> =
                    IdentityDetails(false, TestIdentity("details"))
            },
            recorder(registry)
        )
        identity.provide(identityContext())

        val asyncIdentity = ObservedAsyncIdentityDetailsProvider(
            object : AsyncIdentityDetailsProvider<TestIdentity> {
                override val detailsType: Class<TestIdentity> = TestIdentity::class.java
                override fun provide(context: IdentityProviderContext) =
                    CompletableFuture.completedFuture(IdentityDetails(true, TestIdentity("async")))
            },
            registry
        )
        asyncIdentity.provide(identityContext()).toCompletableFuture().join()

        assertThat(registry)
            .hasObservationWithNameEqualTo(ArcObservationNames.AUTHENTICATION)
            .that()
            .hasLowCardinalityKeyValue(ArcObservationTags.OUTCOME, "anonymous")
            .backToTestObservationRegistry()
            .hasNumberOfObservationsWithNameEqualTo(ArcObservationNames.IDENTITY_DETAILS, 2)
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "unauthorized")
            .hasAnObservationWithAKeyValue(ArcObservationTags.OUTCOME, "authorized")
    }

    private fun recorder(registry: TestObservationRegistry): ArcObservationRecorder =
        ArcObservationRecorder(registry, ArcObservabilityProperties())

    private fun commandOptions(correlationId: UUID = UUID.randomUUID()): CommandExecutionOptions =
        CommandExecutionOptions(correlationId, ArcPrincipal.anonymous(), NoServices)

    private fun queryOptions(correlationId: UUID = UUID.randomUUID()): QueryExecutionOptions =
        QueryExecutionOptions(correlationId, ArcPrincipal.anonymous(), NoServices)

    private fun queryRequest(): QueryRequest = QueryRequest(FullyQualifiedQueryName("example.AllItems"))

    private fun identityContext(): IdentityProviderContext = IdentityProviderContext("id", "name", emptyList())

    private data class TestCommand(val value: String = "secret")
    private data class TestIdentity(val value: String)

    private object NoServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }

    private class ThrowingCommandPipeline(private val failure: Throwable) : CommandPipeline {
        override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> = throw failure
        override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> = throw failure
    }
}
