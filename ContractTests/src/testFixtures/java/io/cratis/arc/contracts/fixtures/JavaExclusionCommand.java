// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;
import jakarta.validation.Valid;

/** Real generated Java handler consuming nested concept edges. */
@Command
@AllowAnonymous
public record JavaExclusionCommand(@Valid JavaExclusionInput input) {
    public String handle() { return "handled"; }
}
