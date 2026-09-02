// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.chronicle.TenantEventStoreResolver;
import io.cratis.chronicle.IEventStore;
import io.cratis.chronicle.java.ReadModelsJavaBridge;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Chronicle-backed Java query bridge using only the explicit Arc tenant namespace. */
final class ChronicleTaskViewReader implements TaskViewReader {
    private final TenantEventStoreResolver eventStoreResolver;

    ChronicleTaskViewReader(TenantEventStoreResolver eventStoreResolver) {
        this.eventStoreResolver = eventStoreResolver;
    }

    @Override
    public CompletionStage<TaskView> byId(String namespace, String id) {
        return CompletableFuture.completedFuture(
            ReadModelsJavaBridge.getInstanceByKey(eventStore(namespace).getReadModels(), TaskView.class, id));
    }

    @Override
    public CompletionStage<List<TaskView>> all(String namespace) {
        return CompletableFuture.completedFuture(
            ReadModelsJavaBridge.getInstances(eventStore(namespace).getReadModels(), TaskView.class));
    }

    private IEventStore eventStore(String namespace) {
        var eventStore = eventStoreResolver.resolve(namespace);
        if (eventStore == null) {
            throw new IllegalStateException("Chronicle event store is unavailable for tenant namespace '" + namespace + "'.");
        }
        if (!namespace.equals(eventStore.getNamespace())) {
            throw new IllegalStateException(
                "Chronicle event store namespace '" + eventStore.getNamespace() + "' does not match '" + namespace + "'.");
        }
        return eventStore;
    }
}
