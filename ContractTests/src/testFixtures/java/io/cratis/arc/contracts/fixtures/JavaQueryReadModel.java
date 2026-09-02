// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.queries.Path;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryHttpMethod;
import io.cratis.arc.queries.QueryHttpMethodType;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryTransport;
import io.cratis.arc.queries.QueryTransportType;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Java record read model fixture with an asynchronous static query. */
@ReadModel
@AllowAnonymous
@Path("/java-models")
@QueryHttpMethod(QueryHttpMethodType.QUERY)
public record JavaQueryReadModel(String value) {
    /** Ordinary static helper that is not a query because it does not return this read-model shape. */
    public static String helperLabel() {
        return "helper";
    }
    /** Returns one model with request and context values in their declared positions. */
    @Path("/java-models/contextual")
    public static JavaQueryReadModel contextualJava(QueryRequest request, String label, QueryContext context) {
        if (context.getRequest() != request) {
            throw new IllegalArgumentException("Query context must carry the exact request instance.");
        }
        return new JavaQueryReadModel(label + "-" + context.getCorrelationId());
    }

    /** Returns one model asynchronously without blocking. */
    @Path("/java-models/by-id")
    @QueryHttpMethod(QueryHttpMethodType.QUERY)
    @QueryTransport(QueryTransportType.REQUEST_RESPONSE)
    public static CompletionStage<JavaQueryReadModel> byId(
        QueryContext context,
        String identifier,
        @FromServices JavaQueryDependency dependency,
        QueryRequest request
    ) {
        if (context.getRequest() != request) {
            throw new IllegalArgumentException("Query context must carry the exact request instance.");
        }
        return dependency.model(identifier);
    }

    /** Returns one direct database-owned page with host paging and sorting adapters. */
    @Path("/java-models/spring-data-direct")
    public static Page<JavaQueryReadModel> springDataJavaDirect(
        Sort sort,
        String label,
        QueryRequest request,
        @FromServices JavaQueryDependency dependency,
        Pageable pageable
    ) {
        if (!pageable.getSort().equals(sort)) {
            throw new IllegalArgumentException("Pageable and Sort must represent the same request sorting.");
        }
        var paging = pageable.isPaged() ? pageable.getPageNumber() + ":" + pageable.getPageSize() : "unpaged";
        var order = sort.getOrderFor(request.getSorting().getField());
        var direction = order == null ? "UNSORTED" : order.getDirection().name();
        return dependency.pageNow(label + "|" + paging + "|" + direction, pageable, 61);
    }

    /** Returns one asynchronous database-owned page with host paging and sorting adapters. */
    @Path("/java-models/spring-data-async")
    public static CompletionStage<Page<JavaQueryReadModel>> springDataAsync(
        QueryRequest request,
        Pageable pageable,
        String label,
        @FromServices JavaQueryDependency dependency,
        Sort sort
    ) {
        if (!pageable.getSort().equals(sort)) {
            throw new IllegalArgumentException("Pageable and Sort must represent the same request sorting.");
        }
        var paging = pageable.isPaged() ? pageable.getPageNumber() + ":" + pageable.getPageSize() : "unpaged";
        var order = sort.getOrderFor(request.getSorting().getField());
        var direction = order == null ? "UNSORTED" : order.getDirection().name();
        return dependency.page(label + "|" + paging + "|" + direction, pageable, 79);
    }

    /** Returns a JDK observable collection. */
    public static Flow.Publisher<List<JavaQueryReadModel>> observeJava(
        QueryRequest request,
        String label,
        @FromServices JavaQueryDependency dependency,
        QueryContext context
    ) {
        if (context.getRequest() != request) {
            throw new IllegalArgumentException("Query context must carry the exact request instance.");
        }
        dependency.observe(label);
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean emitted;

            @Override
            public void request(long count) {
                if (!emitted && count > 0) {
                    emitted = true;
                    subscriber.onNext(List.of(new JavaQueryReadModel(
                        "observable-java-" + label + "-true-" + context.getCorrelationId()
                    )));
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                emitted = true;
            }
        });
    }
}
