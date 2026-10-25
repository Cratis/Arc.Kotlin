// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.json

import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedTypeIdentifiers
import io.cratis.fundamentals.jackson.DerivedTypesModule
import io.cratis.fundamentals.serialization.DerivedType
import io.cratis.fundamentals.serialization.DerivedTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.exc.UnrecognizedPropertyException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import io.cratis.arc.polymorphism.DerivedType as ArcDerivedType

/** Arc honours `io.cratis.fundamentals.serialization.DerivedType` exactly like its own annotation. */
class FundamentalsDerivedTypeAnnotationTest {
    interface Figure

    @DerivedType("circle")
    data class Circle(val radius: Double) : Figure

    @ArcDerivedType("legacy-square")
    data class LegacySquare(val side: Double) : Figure

    @DerivedType("both")
    @ArcDerivedType("both")
    data class SameIdOnBoth(val size: Int) : Figure

    @DerivedType("fundamentals-id")
    @ArcDerivedType("arc-id")
    data class DifferentIdsOnBoth(val size: Int) : Figure

    @DerivedType("")
    data class Blank(val size: Int) : Figure

    class Unannotated : Figure

    interface Other

    @DerivedType("targeted", targetType = Other::class)
    data class Targeted(val size: Int) : Figure

    @DerivedType("fundamentals-only")
    data class FundamentalsOnly(val size: Int)

    interface Gadget

    @DerivedType("gadget-fundamentals-only")
    data class GadgetFundamentalsOnly(val size: Int) : Gadget

    /** Structurally identical to [GadgetFundamentalsOnly] but unannotated: the pre-Q-79 wire shape. */
    data class GadgetUnannotated(val size: Int)

    private fun gadgetMapper() = ArcObjectMapper.create(
        ConcurrentDerivedTypeRegistry().apply { register(Gadget::class.java, GadgetFundamentalsOnly::class.java) }
    )

    private fun strictPlainMapper(failOnUnknown: Boolean) = jacksonMapperBuilder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknown)
        .build()

    private fun registryWith(vararg types: Class<*>) = ConcurrentDerivedTypeRegistry().apply {
        types.forEach { register(Figure::class.java, it) }
    }

    @Test
    fun `a fundamentals annotated derivative round trips with the same wire as the arc annotation`() {
        val mapper = ArcObjectMapper.create(registryWith(Circle::class.java, LegacySquare::class.java))

        val circle = mapper.writeValueAsString(Circle(2.0))
        val square = mapper.writeValueAsString(LegacySquare(2.0))

        assertEquals("""{"radius":2.0,"_derivedTypeId":"circle"}""", circle)
        assertEquals("""{"side":2.0,"_derivedTypeId":"legacy-square"}""", square)
        assertEquals(Circle(2.0), mapper.readValue(circle, Figure::class.java))
        assertEquals(LegacySquare(2.0), mapper.readValue(square, Figure::class.java))
    }

    @Test
    fun `a hierarchy mixing both annotations resolves both identifiers`() {
        val registry = registryWith(Circle::class.java, LegacySquare::class.java)

        assertEquals(Circle::class.java, registry.resolve(Figure::class.java, "circle"))
        assertEquals(LegacySquare::class.java, registry.resolve(Figure::class.java, "legacy-square"))
    }

    @Test
    fun `the same identifier on both annotations is accepted`() {
        val registry = registryWith(SameIdOnBoth::class.java)

        assertEquals("both", registry.idFor(Figure::class.java, SameIdOnBoth::class.java))
    }

    @Test
    fun `different identifiers on both annotations keep the arc identifier on the wire`() {
        val mapper = ArcObjectMapper.create(registryWith(DifferentIdsOnBoth::class.java))

        val json = mapper.writeValueAsString(DifferentIdsOnBoth(3))

        assertEquals("""{"size":3,"_derivedTypeId":"arc-id"}""", json)
        assertEquals(DifferentIdsOnBoth(3), mapper.readValue(json, Figure::class.java))
    }

    @Test
    fun `a blank fundamentals identifier is rejected with the existing message`() {
        val exception = assertThrows(IllegalArgumentException::class.java) { registryWith(Blank::class.java) }

        assertEquals("Derived type identifiers cannot be blank", exception.message)
    }

    @Test
    fun `a missing annotation is rejected with the existing message`() {
        val exception = assertThrows(IllegalArgumentException::class.java) { registryWith(Unannotated::class.java) }

        assertTrue(exception.message.orEmpty().endsWith("must be annotated with @DerivedType"))
    }

    @Test
    fun `targetType is ignored because registrations follow the explicit base`() {
        val registry = registryWith(Targeted::class.java)

        assertEquals(Targeted::class.java, registry.resolve(Figure::class.java, "targeted"))
        assertEquals(null, registry.resolve(Other::class.java, "targeted"))
    }

    @Test
    fun `Q-79 a class carrying only the fundamentals annotation is written with a derived type id`() {
        val mapper = ArcObjectMapper.create()

        val json = mapper.writeValueAsString(FundamentalsOnly(5))

        assertEquals("""{"size":5,"_derivedTypeId":"fundamentals-only"}""", json)
    }

    @Test
    fun `an explicitly registered fundamentals module next to arc writes one derived type id`() {
        val registry = registryWith(Circle::class.java)
        val plain = JsonMapper.builder()
            .addModule(DerivedTypesModule(DerivedTypes.of(Circle::class.java)))
            .build()
        val mapper = ArcObjectMapper.configure(plain, registry)

        val json = mapper.writeValueAsString(Circle(2.0))

        assertEquals(1, Regex("_derivedTypeId").findAll(json).count(), json)
    }

    @Test
    fun `Q-79 after - a fundamentals only class is written with the fundamentals value as derived type id`() {
        val json = gadgetMapper().writeValueAsString(GadgetFundamentalsOnly(7))

        assertEquals("""{"size":7,"_derivedTypeId":"gadget-fundamentals-only"}""", json)
    }

    @Test
    fun `Q-79 registered base reader yields the concrete type and round trips`() {
        val mapper = gadgetMapper()
        val json = mapper.writeValueAsString(GadgetFundamentalsOnly(7))

        assertEquals(GadgetFundamentalsOnly(7), mapper.readValue(json, Gadget::class.java))
    }

    @Test
    fun `Q-79 arc concrete reader tolerates the derived type id it writes`() {
        val mapper = gadgetMapper()
        val json = mapper.writeValueAsString(GadgetFundamentalsOnly(7))

        assertEquals(GadgetFundamentalsOnly(7), mapper.readValue(json, GadgetFundamentalsOnly::class.java))
    }

    @Test
    fun `Q-79 caveat - an older strict concrete reader rejects the added derived type id`() {
        val json = gadgetMapper().writeValueAsString(GadgetFundamentalsOnly(7))

        val exception = assertThrows(UnrecognizedPropertyException::class.java) {
            strictPlainMapper(failOnUnknown = true).readValue(json, GadgetFundamentalsOnly::class.java)
        }

        assertEquals("_derivedTypeId", exception.propertyName)
    }

    @Test
    fun `Q-79 caveat - a lenient concrete reader reads the added derived type id`() {
        val json = gadgetMapper().writeValueAsString(GadgetFundamentalsOnly(7))

        assertEquals(
            GadgetFundamentalsOnly(7),
            strictPlainMapper(failOnUnknown = false).readValue(json, GadgetFundamentalsOnly::class.java)
        )
    }

    @Test
    fun `Q-79 before - with the seam off the class has no identifier so the wire has no derived type id and the strict reader accepts it`() {
        assertNull(DerivedTypeIdentifiers.of(GadgetFundamentalsOnly::class.java, honoursFundamentalsOnly = false))

        // An unannotated twin is exactly what the serializer writes when the identifier is null (pre-Q-79).
        val json = ArcObjectMapper.create().writeValueAsString(GadgetUnannotated(7))

        assertEquals("""{"size":7}""", json)
        assertEquals(
            GadgetUnannotated(7),
            strictPlainMapper(failOnUnknown = true).readValue(json, GadgetUnannotated::class.java)
        )
    }

    @Test
    fun `Q-79 seam - fundamentals only derived types are honoured, reverting is a one line change plus this test`() {
        assertEquals(true, DerivedTypeIdentifiers.honoursFundamentalsOnlyDerivedTypes())
        assertEquals(
            "gadget-fundamentals-only",
            DerivedTypeIdentifiers.of(GadgetFundamentalsOnly::class.java)
        )
    }
}
