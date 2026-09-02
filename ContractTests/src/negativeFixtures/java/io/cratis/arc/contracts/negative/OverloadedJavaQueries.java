// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;

/** Java query overloads remain ambiguous even when every method is static. */
@ReadModel
public record OverloadedJavaQueries(String value) {
    public static OverloadedJavaQueries find() {
        return new OverloadedJavaQueries("default");
    }

    public static OverloadedJavaQueries find(String value) {
        return new OverloadedJavaQueries(value);
    }
}
