// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command

public data class FirstAmbiguousKotlinResponse(public val value: String)
public data class SecondAmbiguousKotlinResponse(public val value: String)

@Command
public class AmbiguousKotlinResponseCommand {
    public fun handle(): Pair<FirstAmbiguousKotlinResponse, SecondAmbiguousKotlinResponse> =
        Pair(FirstAmbiguousKotlinResponse("first"), SecondAmbiguousKotlinResponse("second"))
}
