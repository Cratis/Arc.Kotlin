// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import io.cratis.fundamentals.serialization.DerivedType;

/** Invalid Java command whose model is an interface carrying @DerivedType. */
@Command
public record AnnotatedDerivedTypeJavaInterfaceCommand(AnnotatedDerivedTypeJavaInterface shape) {
    public void handle() {
    }
}

@DerivedType("annotated-java-interface")
interface AnnotatedDerivedTypeJavaInterface {
    String value();
}
