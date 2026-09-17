// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import jakarta.validation.constraints.Digits;

@Command
public record JavaDigitsZeroInteger(
    @Digits(integer = 0, fraction = 2) String value
) {
    public void handle() {}
}
