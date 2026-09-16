// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

@io.cratis.arc.artifacts.Command
public record JavaComputedBodyInput(java.util.List<JavaComputedBodyChild> children) {
    public void handle() { }
}
