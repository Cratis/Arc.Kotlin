// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import jakarta.validation.Valid;
import java.util.concurrent.Flow;

/** Real generated Java one-shot and observable invocations. */
@ReadModel
@AllowAnonymous
public record JavaExclusionView(String value) {
    public static JavaExclusionView findJavaExclusions(@Valid @org.jetbrains.annotations.Nullable JavaExclusionInput input) {
        return new JavaExclusionView(input == null || input.ignored() == null ? "absent" : input.ignored().value());
    }
    public static Flow.Publisher<JavaExclusionView> observeJavaExclusions(@Valid @org.jetbrains.annotations.Nullable JavaExclusionInput input) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean emitted;
            @Override public void request(long count) {
                if (count > 0 && !emitted) {
                    emitted = true;
                    subscriber.onNext(findJavaExclusions(input));
                    subscriber.onComplete();
                }
            }
            @Override public void cancel() { emitted = true; }
        });
    }
}
