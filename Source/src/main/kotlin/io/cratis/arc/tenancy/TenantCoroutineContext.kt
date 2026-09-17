// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element carrying the current [TenantId] for one unit of coroutine work.
 *
 * Using a [CoroutineContext] element rather than a [ThreadLocal] is the correct JVM choice for a
 * coroutine-first codebase: a `ThreadLocal` silently loses its value when a coroutine is resumed
 * on a different thread after a suspension point (e.g. when switching dispatchers). A context
 * element propagates automatically to every child coroutine and is restored correctly across
 * every dispatcher switch.
 *
 * This is the faithful JVM equivalent of `AsyncLocal<TenantId>` used in the .NET reference
 * (`Cratis/Arc/Source/DotNET/Arc.Core/Tenancy/TenantIdAccessor.cs`). The .NET `AsyncLocal<T>`
 * flows with async work just as this element flows with coroutine work; both are invisible
 * across boundaries that do not explicitly propagate their context.
 *
 * Use [withTenant] to establish the tenant for a coroutine scope and [currentTenant] to read it.
 * For Java call paths that have no coroutine context, use [TenantContextBridge].
 *
 * Fallback: when no element is present in the current [CoroutineContext], [currentTenant] returns
 * `null`. This matches the .NET fallback — `TenantIdAccessor` returns [TenantId.NotSet] when the
 * resolver produces nothing; the JVM equivalent is `null` / [TenantId.NOT_SET] depending on
 * caller preference. Neither throws by default; callers that require a tenant must check and
 * throw explicitly.
 */
public class TenantCoroutineContext(
    /** The tenant identifier established for this coroutine scope. */
    public val tenantId: TenantId
) : AbstractCoroutineContextElement(Key) {
    /** Key for locating [TenantCoroutineContext] in a [CoroutineContext]. */
    public companion object Key : CoroutineContext.Key<TenantCoroutineContext>
}
