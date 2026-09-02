// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import java.util.Map;

/** Invalid non-string Java map-key fixture. */
@Command
public record NonStringJavaMapCommand(Map<Integer, String> values) {
    public void handle() {
    }
}
