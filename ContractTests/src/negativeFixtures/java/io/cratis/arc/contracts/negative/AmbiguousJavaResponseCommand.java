// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import kotlin.Pair;

@Command
public final class AmbiguousJavaResponseCommand {
    public Pair<FirstAmbiguousJavaResponse, SecondAmbiguousJavaResponse> handle() {
        return new Pair<>(
            new FirstAmbiguousJavaResponse("first"),
            new SecondAmbiguousJavaResponse("second")
        );
    }
}
