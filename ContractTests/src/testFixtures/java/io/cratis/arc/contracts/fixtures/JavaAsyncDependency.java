// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

/** Dependency used by the Java CompletionStage command fixture. */
public final class JavaAsyncDependency {
    private int invocationCount;

    /** Produces the fixture response. */
    public String respond(String value) {
        invocationCount++;
        return "java:" + value;
    }

    /** Number of dependency invocations. */
    public int getInvocationCount() {
        return invocationCount;
    }
}
