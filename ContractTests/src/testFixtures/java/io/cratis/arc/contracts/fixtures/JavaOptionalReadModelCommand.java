// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import java.util.Optional;

/** Java command whose optional owned read model receives Optional.empty when no keyed row exists. */
@Command
public record JavaOptionalReadModelCommand(@CommandKey String id) {
    /** Supplies a read model for the explicit composition case while leaving ordinary IDs to resolver ownership. */
    public CommandReadModel provide() {
        return id.equals("provided") ? new CommandReadModel(id, "provided") : null;
    }

    /** Returns a value from the optional current read model. */
    public String handle(Optional<CommandReadModel> current) {
        return "java:" + current.map(CommandReadModel::getValue).orElse("none");
    }
}
