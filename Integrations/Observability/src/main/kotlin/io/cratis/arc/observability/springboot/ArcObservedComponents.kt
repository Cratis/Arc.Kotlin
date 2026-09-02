// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot

import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationRequestContext
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.identity.AsyncIdentityDetailsProvider
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

internal interface ArcObservedComponent

internal class ObservedCommandPipeline(
    private val delegate: CommandPipeline,
    private val recorder: ArcObservationRecorder
) : CommandPipeline, ArcObservedComponent {
    override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> =
        record("execute", command, options) { delegate.execute(command, options) }

    override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> =
        record("validate", command, options) { delegate.validate(command, options) }

    private suspend fun record(
        operation: String,
        command: Any,
        options: CommandExecutionOptions,
        block: suspend () -> CommandResult<*>
    ): CommandResult<*> {
        val artifact = command.javaClass.name
        return recorder.record(
            ArcObservationNames.COMMAND,
            "$operation $artifact",
            listOf(
                ArcObservationTags.OPERATION to operation,
                ArcObservationTags.ARTIFACT to artifact
            ),
            options.correlationId,
            ::commandOutcome,
            block
        )
    }
}

internal class ObservedQueryPipeline(
    private val delegate: QueryPipeline,
    private val recorder: ArcObservationRecorder
) : QueryPipeline, ArcObservedComponent {
    override suspend fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*> {
        val query = request.queryName.value
        return recorder.record(
            ArcObservationNames.QUERY,
            "perform $query",
            listOf(
                ArcObservationTags.OPERATION to "perform",
                ArcObservationTags.QUERY to query
            ),
            options.correlationId,
            ::queryOutcome
        ) {
            delegate.perform(request, options)
        }
    }
}

internal class ObservedObservableQueryPipeline(
    private val delegate: ObservableQueryPipeline,
    private val recorder: ArcObservationRecorder
) : ObservableQueryPipeline, ArcObservedComponent {
    override suspend fun open(
        request: QueryRequest,
        options: QueryExecutionOptions,
        transferMode: ObservableQueryTransferMode,
        keyExtractor: ((Any) -> Any?)?
    ): ObservableQueryOpenResult {
        val query = request.queryName.value
        val opened = recorder.record(
            ArcObservationNames.OBSERVABLE_QUERY,
            "open $query",
            observableTags("open", query),
            options.correlationId,
            ::openOutcome
        ) {
            delegate.open(request, options, transferMode, keyExtractor)
        }
        return when (opened) {
            is ObservableQueryOpenResult.Failure -> opened
            is ObservableQueryOpenResult.Stream -> ObservableQueryOpenResult.Stream(
                observedResults(opened.results, query, options)
            )
        }
    }

    private fun observedResults(
        source: Flow<QueryResult<*>>,
        query: String,
        options: QueryExecutionOptions
    ): Flow<QueryResult<*>> = channelFlow {
        val collection = launch {
            recorder.record(
                ArcObservationNames.OBSERVABLE_QUERY,
                "subscription $query",
                observableTags("subscription", query),
                options.correlationId,
                { "completed" }
            ) {
                source.collect { result ->
                    recorder.record(
                        ArcObservationNames.OBSERVABLE_QUERY,
                        "emission $query",
                        observableTags("emission", query),
                        options.correlationId,
                        ::queryOutcome
                    ) {
                        send(result)
                        result
                    }
                }
            }
        }
        collection.invokeOnCompletion { failure -> close(failure) }
        awaitClose { collection.cancel() }
    }.buffer(0)

    private fun observableTags(operation: String, query: String): List<Pair<String, String>> = listOf(
        ArcObservationTags.OPERATION to operation,
        ArcObservationTags.QUERY to query
    )
}

internal class ObservedAuthentication(
    private val delegate: Authentication,
    private val recorder: ArcObservationRecorder
) : Authentication, ArcObservedComponent {
    override val hasHandlers: Boolean
        get() = delegate.hasHandlers

    override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult =
        recorder.record(
            ArcObservationNames.AUTHENTICATION,
            "authenticate",
            listOf(ArcObservationTags.OPERATION to "authenticate"),
            outcome = ::authenticationOutcome
        ) {
            delegate.handleAuthentication(context)
        }
}

internal class ObservedIdentityDetailsProvider<T : Any>(
    private val delegate: IdentityDetailsProvider<T>,
    private val recorder: ArcObservationRecorder
) : IdentityDetailsProvider<T>, ArcObservedComponent {
    override val detailsType: Class<T>
        get() = delegate.detailsType

    override suspend fun provide(context: IdentityProviderContext): IdentityDetails<T> = recorder.record(
        ArcObservationNames.IDENTITY_DETAILS,
        "provide identity details",
        listOf(ArcObservationTags.OPERATION to "provide"),
        outcome = ::identityOutcome
    ) {
        delegate.provide(context)
    }
}

internal class ObservedAsyncIdentityDetailsProvider<T : Any>(
    private val delegate: AsyncIdentityDetailsProvider<T>,
    private val registry: ObservationRegistry
) : AsyncIdentityDetailsProvider<T>, ArcObservedComponent {
    override val detailsType: Class<T>
        get() = delegate.detailsType

    override fun provide(context: IdentityProviderContext): CompletionStage<IdentityDetails<T>> {
        val observation = Observation.createNotStarted(ArcObservationNames.IDENTITY_DETAILS, registry)
            .contextualName("provide identity details")
            .lowCardinalityKeyValue(ArcObservationTags.OPERATION, "provide")
            .start()
        val source = try {
            delegate.provide(context)
        } catch (exception: Throwable) {
            observation.lowCardinalityKeyValue(ArcObservationTags.OUTCOME, "error")
            observation.error(exception)
            observation.stop()
            throw exception
        }
        val result = CompletableFuture<IdentityDetails<T>>()
        val stopped = AtomicBoolean()
        source.whenComplete { details, failure ->
            if (failure == null) {
                finish(observation, stopped, identityOutcome(details), null)
                result.complete(details)
            } else {
                val cause = unwrapCompletionFailure(failure)
                val outcome = if (cause is CancellationException) "cancelled" else "error"
                finish(observation, stopped, outcome, if (cause is CancellationException) null else cause)
                if (cause is CancellationException) result.cancel(false) else result.completeExceptionally(cause)
            }
        }
        result.whenComplete { _, _ ->
            if (result.isCancelled) {
                source.toCompletableFuture().cancel(true)
                finish(observation, stopped, "cancelled", null)
            }
        }
        return result
    }

    private fun finish(
        observation: Observation,
        stopped: AtomicBoolean,
        outcome: String,
        failure: Throwable?
    ) {
        if (!stopped.compareAndSet(false, true)) return
        observation.lowCardinalityKeyValue(ArcObservationTags.OUTCOME, outcome)
        failure?.let(observation::error)
        observation.stop()
    }

    private fun unwrapCompletionFailure(failure: Throwable): Throwable = when (failure) {
        is CompletionException, is ExecutionException -> failure.cause ?: failure
        else -> failure
    }
}

private fun commandOutcome(result: CommandResult<*>): String = when {
    result.hasExceptions -> "error"
    !result.isAuthorized -> "unauthorized"
    !result.isValid -> "invalid"
    else -> "success"
}

private fun queryOutcome(result: QueryResult<*>): String = when {
    result.hasExceptions -> "error"
    !result.isAuthorized -> "unauthorized"
    !result.isValid -> "invalid"
    !result.isReady -> "not_ready"
    else -> "success"
}

private fun openOutcome(result: ObservableQueryOpenResult): String = when (result) {
    is ObservableQueryOpenResult.Failure -> queryOutcome(result.result)
    is ObservableQueryOpenResult.Stream -> "opened"
}

private fun authenticationOutcome(result: AuthenticationResult): String = when {
    result.isAuthenticated -> "authenticated"
    result.failure != null -> "rejected"
    else -> "anonymous"
}

private fun identityOutcome(result: IdentityDetails<*>): String =
    if (result.isUserAuthorized) "authorized" else "unauthorized"
