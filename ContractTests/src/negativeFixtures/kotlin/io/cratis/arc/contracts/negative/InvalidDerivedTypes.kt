// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import io.cratis.arc.polymorphism.DerivedType

@DerivedType("")
public data class BlankDerivedTypeIdShape(public val value: String)

@DerivedType("annotated-kotlin-interface")
public interface AnnotatedDerivedTypeInterface {
    public val value: String
}

@Command
public data class BlankDerivedTypeIdCommand(public val shape: BlankDerivedTypeIdShape) {
    public fun handle(): Unit = Unit
}

@Command
public data class AnnotatedDerivedTypeInterfaceCommand(public val shape: AnnotatedDerivedTypeInterface) {
    public fun handle(): Unit = Unit
}
