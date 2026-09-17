// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("TenantContexts")

package io.cratis.arc.tenancy

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * Returns the [TenantId] established in the current coroutine context, or `null` when no tenant
 * has been set.
 *
 * The value is inherited from the nearest enclosing [withTenant] call or any other mechanism that
 * installed a [TenantCoroutineContext] element. Child coroutines inherit the element automatically;
 * it is not lost across dispatcher switches.
 *
 * A `null` return means no tenant is active — callers that require one should throw explicitly
 * rather than proceeding with a default, to surface a configuration error early.
 */
public suspend fun currentTenant(): TenantId? =
    coroutineContext[TenantCoroutineContext]?.tenantId

/**
 * Returns the [TenantId] established in the current coroutine context, or [TenantId.NOT_SET]
 * when no tenant has been set.
 *
 * Prefer [currentTenant] in code that must distinguish "no tenant configured" from "default
 * tenant explicitly selected". Use this overload only when a non-null sentinel is genuinely
 * correct (for example, forwarding to infrastructure that uses [TenantId.NOT_SET] as its own
 * sentinel).
 */
public suspend fun currentTenantOrNotSet(): TenantId =
    coroutineContext[TenantCoroutineContext]?.tenantId ?: TenantId.NOT_SET

/**
 * Executes [block] with [tenantId] established in the coroutine context and returns its result.
 *
 * The [tenantId] is visible to [currentTenant], [currentTenantOrNotSet], and any child coroutines
 * launched inside [block]. The previous tenant context, if any, is restored when [block] returns
 * or throws.
 *
 * [block] receives the tenant-carrying [CoroutineScope] as its receiver. That receiver is what makes
 * the inheritance promise true: a `launch` or `async` written inside [block] binds to this scope and
 * therefore carries the tenant. Without it those builders would bind to the enclosing scope instead
 * and silently observe no tenant.
 */
public suspend fun <T> withTenant(tenantId: TenantId, block: suspend CoroutineScope.() -> T): T =
    withContext(TenantCoroutineContext(tenantId), block)
