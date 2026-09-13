// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.queries.QueryRequest;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Flow;

/** Real generated Java invocation checks scalar and boxed-array types before emitting. */
@ReadModel
@AllowAnonymous
public record JavaObservableSnapshot(String value) {
    /** Remains active after emission until explicit transport cancellation. */
    public static Flow.Publisher<JavaObservableSnapshot> observeJavaSnapshot(
        UUID id, LocalDate date, JavaOrderId concept, FixtureState ordinary,
        ExplicitFixtureState coded, Long small, UUID[] ids, Long[] longs, QueryRequest request
    ) {
        if (!id.equals(concept.value()) || ids.length != 1 || !ids[0].equals(id)
            || ids.getClass().getComponentType() != UUID.class
            || longs.getClass().getComponentType() != Long.class
            || longs.length != 1 || longs[0].longValue() != small.longValue()
            || ordinary != FixtureState.Active || coded.value() != 17) {
            throw new IllegalArgumentException("Declared argument types and values must survive subscription capture.");
        }
        var value = date.plusDays(1) + "|" + (small.longValue() + 1) + "|java|"
            + request.getPaging().getPage() + "|" + request.getSorting().getField();
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean emitted;
            @Override
            public void request(long count) {
                if (count > 0 && !emitted) {
                    emitted = true;
                    subscriber.onNext(new JavaObservableSnapshot(value));
                }
            }
            @Override
            public void cancel() { emitted = true; }
        });
    }
}
