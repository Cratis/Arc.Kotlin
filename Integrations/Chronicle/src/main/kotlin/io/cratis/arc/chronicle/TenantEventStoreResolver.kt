// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.chronicle.IEventStore

/** Resolves the Chronicle event store for an explicit command tenant namespace. */
public fun interface TenantEventStoreResolver {
    /** Returns the store for [tenantNamespace], or `null` when the namespace cannot be served safely. */
    public fun resolve(tenantNamespace: String?): IEventStore?
}

/** Provides a Chronicle event store for a non-null command tenant namespace. */
public fun interface TenantEventStoreProvider {
    /** Returns the store for [tenantNamespace], or `null` when this provider does not serve it. */
    public fun provide(tenantNamespace: String): IEventStore?
}

internal fun tenantEventStoreResolver(
    defaultEventStore: IEventStore,
    provider: TenantEventStoreProvider? = null
): TenantEventStoreResolver = TenantEventStoreResolver { tenantNamespace ->
    if (tenantNamespace == null) {
        defaultEventStore
    } else {
        provider?.provide(tenantNamespace)
            ?: defaultEventStore.takeIf { it.namespace == tenantNamespace }
    }
}
