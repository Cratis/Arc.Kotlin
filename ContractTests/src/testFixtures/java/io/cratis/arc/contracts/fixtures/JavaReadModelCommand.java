// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;

/** Java command whose generated handler resolves its current read model by command key. */
@Command
public record JavaReadModelCommand(@CommandKey String id) {
    /** Returns a value from the contextually resolved current read model. */
    public String handle(CommandReadModel current) {
        return "java:" + current.getValue();
    }
}
