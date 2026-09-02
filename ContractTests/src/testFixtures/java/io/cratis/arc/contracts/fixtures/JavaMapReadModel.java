// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.ReadModel;
import java.util.List;
import java.util.Map;

/** Java read-model fixture proving map fields remain JSON objects while the query result stays model-shaped. */
@ReadModel
public record JavaMapReadModel(
    Map<String, String> strings,
    Map<String, List<Integer>> numbers,
    Map<String, Map<String, Boolean>> nested,
    @Nullable Map<String, String> optional
) {
    /** Returns a deterministic recursive map model without accepting maps as query parameters. */
    public static JavaMapReadModel getJavaMap() {
        return new JavaMapReadModel(
            Map.of("language", "java"),
            Map.of("values", List.of(3, 4)),
            Map.of("flags", Map.of("ready", true)),
            null
        );
    }
}
