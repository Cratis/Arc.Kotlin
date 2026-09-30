// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel as ArcReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.chronicle.TenantEventStoreResolver
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryContext
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.observation.Reducer
import io.cratis.chronicle.readModels.ReadModel as ChronicleReadModel
import kotlinx.coroutines.flow.Flow

@ArcReadModel
@ChronicleReadModel
@AllowAnonymous
public data class Author(val id: String = "", val name: String = "") {
    public companion object {
        @JvmStatic
        @Path("/api/authors")
        public fun allAuthors(context: QueryContext, @FromServices resolver: TenantEventStoreResolver): Flow<List<Author>> {
            val namespace = requireNotNull(context.tenantNamespace) { "A tenant namespace is required." }
            val store = requireNotNull(resolver.resolve(namespace)) { "No event store for '$namespace'." }
            check(store.namespace == namespace) { "Unexpected event store namespace '${store.namespace}'." }
            return store.readModels.materialized.observeInstances(Author::class, 0, 50)
        }
    }
}

@Reducer
public class AuthorReducer {
    public fun registered(event: AuthorRegistered, state: Author?, context: EventContext): Author =
        Author(id = context.eventSourceId, name = event.name)
}
