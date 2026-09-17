// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import org.hibernate.validator.constraints.Range;

@Command
public record JavaRangeOnNonNumeric(
    @Range(min = 1, max = 10) boolean value
) {
    public void handle() {}
}
