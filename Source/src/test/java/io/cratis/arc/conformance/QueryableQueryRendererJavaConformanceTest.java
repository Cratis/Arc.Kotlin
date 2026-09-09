// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.queries.BlockingQueryRendererFor;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryRendererResult;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryableQueryRenderer;
import io.cratis.arc.results.PagingInfo;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Verifies the renderer's generic class token and inherited Java default bridge. */
final class QueryableQueryRendererJavaConformanceTest {
    @Test
    void iterableTokenAndDefaultRenderBridgeRemainJavaFriendly() {
        BlockingQueryRendererFor<Iterable<?>> renderer = new QueryableQueryRenderer();
        Class<Iterable<?>> token = renderer.queryType();
        List<String> values = List.of("first", "second");
        Iterable<?> query = token.cast(values);
        FullyQualifiedQueryName name = new FullyQualifiedQueryName("Tests.Models.all");
        QueryContext context = new QueryContext(
            UUID.randomUUID(), new QueryRequest(name), name, ArcPrincipal.anonymous(),
            null, null, new ServiceResolver() {
                @Override
                public <T> T resolve(Class<T> type) {
                    return null;
                }
            }, null, false);

        QueryRendererResult rendered = renderer.render(
            query, new QueryRendererResult(values, new PagingInfo(0, 0, 0)), context)
            .toCompletableFuture().join();

        assertSame(Iterable.class, token);
        assertSame(values, query);
        assertEquals(values, rendered.getData());
        assertEquals(2L, rendered.getPaging().getTotalItems());
        assertEquals(0, renderer.order());
    }
}
