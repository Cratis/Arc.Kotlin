// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.polymorphism.DerivedType

public open class ConcretePropertyBase(public val name: String = "base")

@DerivedType("concrete-intermediate")
public open class ConcretePropertyIntermediate : ConcretePropertyBase()

@DerivedType("concrete-leaf")
public class ConcretePropertyLeaf : ConcretePropertyIntermediate()

public open class TransitivePropertyBase
public open class TransitivePropertyMiddle : TransitivePropertyBase()

@DerivedType("transitive-leaf")
public class TransitivePropertyLeaf : TransitivePropertyMiddle()

@Command
public data class ConcretePropertyCommand(
    public val direct: ConcretePropertyBase,
    public val nullable: ConcretePropertyBase?,
    public val list: List<ConcretePropertyBase>,
    public val array: Array<ConcretePropertyBase>,
    public val nullableElements: List<ConcretePropertyBase?>,
    public val transitive: TransitivePropertyBase,
    public val annotatedIntermediate: ConcretePropertyIntermediate
) {
    public fun handle() { }
}

public data class ConcretePropertyNestedDto(public val value: ConcretePropertyBase)

@Command
public data class ConcretePropertyNestedCommand(public val nested: ConcretePropertyNestedDto) {
    public fun handle() { }
}

@ReadModel
public data class ConcretePropertyReadModel(public val value: ConcretePropertyBase) {
    public companion object {
        @JvmStatic
        public fun find(): ConcretePropertyReadModel = ConcretePropertyReadModel(ConcretePropertyBase())
    }
}
