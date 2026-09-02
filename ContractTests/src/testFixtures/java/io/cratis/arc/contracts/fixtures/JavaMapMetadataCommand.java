// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;
import java.util.List;
import java.util.Map;

/** Java command fixture covering recursive maps with non-reserved string keys and empty maps. */
@Command
public record JavaMapMetadataCommand(
    Map<String, String> strings,
    Map<String, List<Integer>> numbers,
    Map<String, Map<String, Boolean>> nested,
    @Nullable Map<String, String> optional
) {
    /** Creates an empty fixture. */
    public JavaMapMetadataCommand() {
        this(Map.of(), Map.of(), Map.of(), null);
    }

    /** Completes without a client response. */
    public void handle() {
    }
}
