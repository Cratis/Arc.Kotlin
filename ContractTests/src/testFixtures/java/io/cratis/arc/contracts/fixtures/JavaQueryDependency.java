// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Java dependency used by generated asynchronous query performer contracts. */
public final class JavaQueryDependency {
    private int invocationCount;

    /** Creates one asynchronously completed read model. */
    public CompletionStage<JavaQueryReadModel> model(String identifier) {
        invocationCount++;
        return CompletableFuture.completedFuture(new JavaQueryReadModel("java-" + identifier));
    }

    /** Creates one asynchronously completed database-owned page. */
    public CompletionStage<Page<JavaQueryReadModel>> page(String value, Pageable pageable, long totalElements) {
        invocationCount++;
        return CompletableFuture.completedFuture(
            new PageImpl<>(java.util.List.of(new JavaQueryReadModel(value)), pageable, totalElements)
        );
    }

    /** Creates one synchronously completed database-owned page. */
    public Page<JavaQueryReadModel> pageNow(String value, Pageable pageable, long totalElements) {
        invocationCount++;
        return new PageImpl<>(java.util.List.of(new JavaQueryReadModel(value)), pageable, totalElements);
    }

    /** Records one synchronous observable-query service invocation. */
    public void observe(String identifier) {
        invocationCount++;
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("Observable identifier must not be blank.");
        }
    }

    /** Gets the number of service invocations. */
    public int getInvocationCount() {
        return invocationCount;
    }
}
