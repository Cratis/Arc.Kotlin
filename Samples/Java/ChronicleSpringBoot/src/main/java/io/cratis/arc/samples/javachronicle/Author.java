// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.chronicle.TenantEventStoreResolver;
import io.cratis.arc.queries.Path;
import io.cratis.arc.queries.QueryContext;
import io.cratis.chronicle.java.ReadModelsJavaBridge;
import java.util.List;
import java.util.concurrent.Flow;

@io.cratis.arc.artifacts.ReadModel
@io.cratis.chronicle.readModels.ReadModel
@AllowAnonymous
public record Author(String id, String name) {
    @Path("/api/authors")
    public static Flow.Publisher<List<Author>> allAuthors(
        QueryContext context, @FromServices TenantEventStoreResolver resolver
    ) {
        var namespace = context.getTenantNamespace();
        if (namespace == null) throw new IllegalStateException("A tenant namespace is required.");
        var store = resolver.resolve(namespace);
        if (store == null) throw new IllegalStateException("No event store for '" + namespace + "'.");
        if (!namespace.equals(store.getNamespace())) throw new IllegalStateException("Unexpected event store namespace.");
        return ReadModelsJavaBridge.observeMaterializedInstancesPublisher(store.getReadModels(), Author.class, 0, 50);
    }
}
