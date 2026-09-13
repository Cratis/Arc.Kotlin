// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.commands.await
import io.cratis.arc.json.ArcObjectMapper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Decision returned by an observable-query emission guard, ordered from least to most restrictive. */
public enum class ObservableQueryEmissionVerdict {
    ALLOW,
    SUPPRESS,
    DENY_AND_TERMINATE
}

/** Subscription context supplied for every observable emission; [data] retains pipeline ownership. */
public class ObservableQueryEmissionContext(
    public val queryName: FullyQualifiedQueryName,
    arguments: Map<String, Any?>,
    public val principal: ArcPrincipal,
    public val tenantId: String?,
    public val tenantNamespace: String?,
    public val correlationId: UUID,
    public val serviceResolver: ServiceResolver,
    public val isFirstEmission: Boolean,
    public val data: Any?
) {
    /** Shallow read-only map; the default aggregator independently reconstructs supported values per dispatch and guard. */
    public val arguments: Map<String, Any?> = java.util.Collections.unmodifiableMap(LinkedHashMap(arguments))
}

/** Re-checks whether one observable emission may be delivered. */
public fun interface GuardObservableQueryEmission {
    /** Returns the emission verdict. Failures are treated as deny-and-terminate. */
    public fun guard(context: ObservableQueryEmissionContext): CompletionStage<ObservableQueryEmissionVerdict>
}

/** Blocking implementation convenience for guards without asynchronous work. */
public fun interface BlockingObservableQueryEmissionGuard : GuardObservableQueryEmission {
    /** Returns the verdict synchronously. */
    public fun guardBlocking(context: ObservableQueryEmissionContext): ObservableQueryEmissionVerdict

    override fun guard(context: ObservableQueryEmissionContext): CompletionStage<ObservableQueryEmissionVerdict> =
        CompletableFuture.completedFuture(guardBlocking(context))
}

/** Aggregates pluggable observable emission guards. */
public interface ObservableQueryEmissionGuards {
    /** Whether dispatch has any work to do. */
    public val hasGuards: Boolean

    /** Returns the most restrictive verdict; deny short-circuits and failures fail closed. */
    public suspend fun guard(context: ObservableQueryEmissionContext): ObservableQueryEmissionVerdict
}

/**
 * Immutable guard registry with bounded per-dispatch argument isolation. Unsupported or uncopyable arguments deny
 * before any guard runs. The default mapper supports concrete single-scalar concepts, not general model cloning.
 * Custom concept codecs must deterministically preserve scalar value/type and reconstruct independent fresh instances;
 * runtime identity checks reject obvious shared codecs, but cannot prove arbitrary application code independent.
 */
public class DefaultObservableQueryEmissionGuards(guards: Iterable<GuardObservableQueryEmission> = emptyList()) :
    ObservableQueryEmissionGuards {
    private val guards = java.util.List.copyOf(guards.toList())
    private var mapper: ObjectMapper = ArcObjectMapper.create()

    /** Uses the authoritative application mapper for supported concrete single-scalar concepts. */
    public constructor(guards: Iterable<GuardObservableQueryEmission>, mapper: ObjectMapper) : this(guards) {
        this.mapper = mapper
    }

    override val hasGuards: Boolean get() = guards.isNotEmpty()

    override suspend fun guard(context: ObservableQueryEmissionContext): ObservableQueryEmissionVerdict {
        if (!hasGuards) return ObservableQueryEmissionVerdict.ALLOW
        val coroutineContext = currentCoroutineContext()
        try {
            coroutineContext.ensureActive()
            val arguments = ObservableQueryArgumentSnapshot(mapper, coroutineContext).copies(context.arguments, guards.size)
            var aggregate = ObservableQueryEmissionVerdict.ALLOW
            for ((index, guard) in guards.withIndex()) {
                coroutineContext.ensureActive()
                val verdict = guard.guard(copyContext(context, arguments[index])).await()
                coroutineContext.ensureActive()
                if (verdict == ObservableQueryEmissionVerdict.DENY_AND_TERMINATE) return verdict
                if (verdict.ordinal > aggregate.ordinal) aggregate = verdict
            }
            return aggregate
        } catch (_: Exception) {
            // Caller cancellation must not become an authorization decision; an independently cancelled stage still denies.
            coroutineContext.ensureActive()
            return ObservableQueryEmissionVerdict.DENY_AND_TERMINATE
        }
    }

    private fun copyContext(context: ObservableQueryEmissionContext, arguments: Map<String, Any?>): ObservableQueryEmissionContext =
        ObservableQueryEmissionContext(
            context.queryName,
            arguments,
            context.principal,
            context.tenantId,
            context.tenantNamespace,
            context.correlationId,
            context.serviceResolver,
            context.isFirstEmission,
            context.data
        )
}
