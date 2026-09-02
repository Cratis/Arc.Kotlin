// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import kotlin.Pair;

/** Java aggregate command with one client response and one declaratively handled value. */
@Command
public final class JavaPairResponseCommand {
    /** Handles the command with ordered aggregate values. */
    public Pair<JavaAggregateClientResponse, HandledResponse> handle() {
        return new Pair<>(new JavaAggregateClientResponse("client"), new HandledResponse("handled"));
    }
}
