// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;

/** Makes the invalid generic map interface reachable from generated metadata. */
@Command
public record GenericJavaMapContractCommand(GenericJavaMapContract contract) {
    public void handle() {
    }
}
