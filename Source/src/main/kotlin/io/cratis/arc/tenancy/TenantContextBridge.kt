// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import java.util.concurrent.Callable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking

/**
 * Bridge for ordinary blocking Java call paths that have no coroutine context.
 *
 * Kotlin suspend code should use [withTenant] and [currentTenant] directly. This bridge exists
 * for Java callers that execute on the calling thread without any coroutine infrastructure, where
 * a [TenantCoroutineContext] element cannot be installed through the normal coroutine scope
 * mechanism.
 *
 * The bridge uses [runBlocking] to create a minimal coroutine scope on the calling thread and
 * installs the supplied [TenantId] in that scope's context. The [Callable] or [Runnable] body
 * runs synchronously on the same thread; any suspension inside it blocks the calling thread.
 * Do not call from within an active coroutine — use [withTenant] instead.
 *
 * A companion [ThreadLocal] is kept in sync with the coroutine context element via
 * [kotlinx.coroutines.asContextElement] so that ordinary Java code inside the body can read
 * [currentTenant] without entering a second coroutine scope. This mirrors the pattern
 * established by [io.cratis.arc.java.BlockingPipelineGuard] for bridging coroutine-context
 * state into blocking Java call paths: safety-state and session-like bridging state both use
 * a `ThreadLocal` + `asContextElement` pair so that the value is set on the thread when the
 * coroutine enters it and restored when the coroutine leaves.
 *
 * **Why the original `runBlocking { currentTenant() }` approach was unsound:**
 * [runBlocking] starts a fresh coroutine that does not inherit context from any outer coroutine.
 * Calling `coroutineContext[TenantCoroutineContext]` inside that fresh coroutine always returns
 * `null`, regardless of any enclosing [withTenant] scope. A [ThreadLocal] maintained in sync
 * with the coroutine context element is the only reliable mechanism for reading coroutine-context
 * values from plain blocking Java code on the same thread.
 *
 * Fallback (no tenant in scope): [currentTenant] returns `null` when called outside any
 * [withTenant] scope, matching [io.cratis.arc.tenancy.currentTenant]'s `null` fallback.
 */
public object TenantContextBridge {
    private val tenantLocal = ThreadLocal<TenantId?>()

    /**
     * Executes [block] on the calling thread with [tenantId] visible to [currentTenant]
     * and returns the result.
     *
     * Java usage:
     * ```java
     * String result = TenantContextBridge.withTenant(tenantId, () -> {
     *     TenantId current = TenantContextBridge.currentTenant(); // == tenantId
     *     return doWork(current);
     * });
     * ```
     */
    @JvmStatic
    public fun <T> withTenant(tenantId: TenantId, block: Callable<T>): T =
        runBlocking(TenantCoroutineContext(tenantId) + tenantLocal.asContextElement(tenantId)) { block.call() }

    /**
     * Executes [block] on the calling thread with [tenantId] visible to [currentTenant].
     */
    @JvmStatic
    public fun withTenant(tenantId: TenantId, block: Runnable): Unit =
        runBlocking(TenantCoroutineContext(tenantId) + tenantLocal.asContextElement(tenantId)) { block.run() }

    /**
     * Returns the [TenantId] from the nearest enclosing [withTenant] scope on the calling thread,
     * or `null` when called outside any such scope.
     *
     * This reads from a [ThreadLocal] that [withTenant] installs and tears down for the duration
     * of the blocking scope via [kotlinx.coroutines.asContextElement]. It is safe to call from
     * plain Java code inside the [Callable] or [Runnable] passed to [withTenant]. Do not call
     * from within a coroutine — use the suspend [io.cratis.arc.tenancy.currentTenant] instead.
     */
    @JvmStatic
    public fun currentTenant(): TenantId? = tenantLocal.get()
}
