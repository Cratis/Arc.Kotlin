// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.concurrent.Flow;

@ReadModel
@AllowAnonymous
public record JavaFundamentalsExclusionView(String value) {
    public static JavaFundamentalsExclusionView findJavaFundamentalsExclusions(@org.jetbrains.annotations.Nullable JavaFundamentalsExclusionInput input) {
        return new JavaFundamentalsExclusionView(input == null || input.ignored() == null ? "absent" : input.ignored().value());
    }
    public static Flow.Publisher<JavaFundamentalsExclusionView> observeJavaFundamentalsExclusions(@org.jetbrains.annotations.Nullable JavaFundamentalsExclusionInput input) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean emitted;
            @Override public void request(long count) {
                if (count > 0 && !emitted) {
                    emitted = true;
                    subscriber.onNext(findJavaFundamentalsExclusions(input));
                    subscriber.onComplete();
                }
            }
            @Override public void cancel() { emitted = true; }
        });
    }
}
