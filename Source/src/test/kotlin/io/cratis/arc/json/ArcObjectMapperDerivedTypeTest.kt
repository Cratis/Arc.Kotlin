// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import tools.jackson.databind.DatabindException
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins what happens to a `@DerivedType` value whose registration is missing.
 *
 * A registry that holds a base type but not one of its derivatives used to write a discriminator that nothing could
 * read back, so the value only travelled one way. Serialization now refuses that value. A type whose hierarchy has no
 * registrations at all is a different situation and keeps writing its discriminator, because that is how an
 * unconfigured registry looks and refusing it would take away serialization that works today.
 */
class ArcObjectMapperDerivedTypeTest {
    @Test
    fun `registered derivatives round trip through the discriminator`() {
        val mapper = ArcObjectMapper.create(registryWith(Circle::class.java, Square::class.java))

        val json = mapper.writeValueAsString(Square(2.0))

        assertEquals("""{"side":2.0,"_derivedTypeId":"square"}""", json)
        assertEquals(Square(2.0), mapper.readValue(json, Shape::class.java))
    }

    @Test
    fun `serializing a derivative missing from a registered hierarchy names the base type`() {
        val mapper = ArcObjectMapper.create(registryWith(Circle::class.java))

        val exception = assertThrows(DatabindException::class.java) {
            mapper.writeValueAsString(Square(2.0))
        }

        assertTrue(exception.message.orEmpty().contains(Square::class.java.name))
        assertTrue(exception.message.orEmpty().contains(Shape::class.java.name))
    }

    @Test
    fun `the refusal covers a derivative nested inside another value`() {
        val mapper = ArcObjectMapper.create(registryWith(Circle::class.java))

        assertThrows(DatabindException::class.java) {
            mapper.writeValueAsString(Drawing(Square(2.0)))
        }
    }

    @Test
    fun `a concrete base class rejects its unregistered subclass the same way`() {
        val registry = ConcurrentDerivedTypeRegistry()
        registry.register(Vehicle::class.java, Car::class.java)
        val mapper = ArcObjectMapper.create(registry)

        assertThrows(DatabindException::class.java) {
            mapper.writeValueAsString(Truck(3))
        }
    }

    @Test
    fun `a hierarchy with no registrations keeps writing its discriminator`() {
        val json = ArcObjectMapper.create().writeValueAsString(Square(2.0))

        assertEquals("""{"side":2.0,"_derivedTypeId":"square"}""", json)
    }

    @Test
    fun `reading an unregistered identifier still reports the identifier and base type`() {
        val mapper = ArcObjectMapper.create(registryWith(Circle::class.java))

        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue("""{"side":2.0,"_derivedTypeId":"square"}""", Shape::class.java)
        }

        assertTrue(exception.message.orEmpty().contains("square"))
        assertTrue(exception.message.orEmpty().contains(Shape::class.java.name))
    }

    private fun registryWith(vararg derivedTypes: Class<*>): ConcurrentDerivedTypeRegistry {
        val registry = ConcurrentDerivedTypeRegistry()
        derivedTypes.forEach { derivedType -> registry.register(Shape::class.java, derivedType) }
        return registry
    }
}

interface Shape

@DerivedType("circle")
data class Circle(val radius: Double) : Shape

@DerivedType("square")
data class Square(val side: Double) : Shape

data class Drawing(val shape: Shape)

open class Vehicle(val wheels: Int) {
    override fun equals(other: Any?): Boolean = other is Vehicle && other.wheels == wheels
    override fun hashCode(): Int = wheels
}

@DerivedType("car")
class Car(wheels: Int) : Vehicle(wheels)

@DerivedType("truck")
class Truck(wheels: Int) : Vehicle(wheels)
