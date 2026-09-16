// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

@io.cratis.arc.artifacts.Command
@io.cratis.arc.authorization.AllowAnonymous
public record JavaIgnoreContractCommand(JavaIgnoreContractInput input) {
    public String handle() { return "accepted"; }
}
