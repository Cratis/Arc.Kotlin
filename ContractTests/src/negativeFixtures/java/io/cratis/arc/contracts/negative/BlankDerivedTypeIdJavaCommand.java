// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.polymorphism.DerivedType;

/** Invalid Java command whose model carries a blank @DerivedType id. */
@Command
public record BlankDerivedTypeIdJavaCommand(BlankDerivedTypeIdJavaShape shape) {
    public void handle() {
    }
}

@DerivedType(id = "")
record BlankDerivedTypeIdJavaShape(String value) {
}
