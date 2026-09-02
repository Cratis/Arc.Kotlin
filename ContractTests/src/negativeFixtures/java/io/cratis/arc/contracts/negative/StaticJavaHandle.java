// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;

@Command
public record StaticJavaHandle(String value) {
    public static String handle() {
        return "static";
    }
}
