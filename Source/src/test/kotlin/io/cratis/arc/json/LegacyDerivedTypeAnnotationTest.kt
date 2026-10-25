// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.json

import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException

/** The deprecated `io.cratis.arc.polymorphism.DerivedType` is still honoured. */
class LegacyDerivedTypeAnnotationTest {
    interface Animal

    @DerivedType(id = "dog")
    data class Dog(val name: String) : Animal

    @DerivedType(id = "cat")
    data class Cat(val name: String) : Animal

    @Test
    fun `a legacy annotated derivative round trips through the discriminator`() {
        val registry = ConcurrentDerivedTypeRegistry().apply { register(Animal::class.java, Dog::class.java) }
        val mapper = ArcObjectMapper.create(registry)

        val json = mapper.writeValueAsString(Dog("rex"))

        assertEquals("""{"name":"rex","_derivedTypeId":"dog"}""", json)
        assertEquals(Dog("rex"), mapper.readValue(json, Animal::class.java))
    }

    @Test
    fun `a legacy derivative missing from a registered hierarchy is rejected on write`() {
        val registry = ConcurrentDerivedTypeRegistry().apply { register(Animal::class.java, Dog::class.java) }
        val mapper = ArcObjectMapper.create(registry)

        assertThrows(DatabindException::class.java) { mapper.writeValueAsString(Cat("tom")) }
    }
}
