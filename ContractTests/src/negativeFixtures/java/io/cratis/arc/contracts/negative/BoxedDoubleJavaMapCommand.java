// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import java.util.Map;

/** Invalid boxed Double Java map value fixture. */
@Command
public record BoxedDoubleJavaMapCommand(Map<String, Double> values) {
    public void handle() {
    }
}
