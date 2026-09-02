// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.commands.await
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Decision returned by an observable-query emission guard, ordered from least to most restrictive. */
public enum class ObservableQueryEmissionVerdict {
    ALLOW,
    SUPPRESS,
    DENY_AND_TERMINATE
}

/** Immutable subscription and caller snapshot supplied for every observable emission. */
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
    /** Captured caller arguments, defensively copied for each guard dispatch. */
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

/** Default immutable guard registry. */
public class DefaultObservableQueryEmissionGuards(guards: Iterable<GuardObservableQueryEmission> = emptyList()) :
    ObservableQueryEmissionGuards {
    private val guards = java.util.List.copyOf(guards.toList())

    override val hasGuards: Boolean get() = guards.isNotEmpty()

    override suspend fun guard(context: ObservableQueryEmissionContext): ObservableQueryEmissionVerdict {
        var aggregate = ObservableQueryEmissionVerdict.ALLOW
        for (guard in guards) {
            val verdict = try {
                guard.guard(copyContext(context)).await()
            } catch (_: Exception) {
                return ObservableQueryEmissionVerdict.DENY_AND_TERMINATE
            }
            if (verdict == ObservableQueryEmissionVerdict.DENY_AND_TERMINATE) return verdict
            if (verdict.ordinal > aggregate.ordinal) aggregate = verdict
        }
        return aggregate
    }

    private fun copyContext(context: ObservableQueryEmissionContext): ObservableQueryEmissionContext =
        ObservableQueryEmissionContext(
            context.queryName,
            context.arguments,
            context.principal,
            context.tenantId,
            context.tenantNamespace,
            context.correlationId,
            context.serviceResolver,
            context.isFirstEmission,
            context.data
        )
}
