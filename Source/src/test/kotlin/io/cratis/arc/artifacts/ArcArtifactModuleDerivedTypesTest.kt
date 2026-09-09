// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

import tools.jackson.databind.DatabindException
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import io.cratis.arc.polymorphism.DerivedTypeRegistrar
import io.cratis.arc.polymorphism.DerivedTypeRegistration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The host-agnostic seam that turns generated module metadata into a populated registry.
 *
 * A module carries its base-to-derivative mappings as real class references, so any host - not only Spring - can hand
 * them to a registry and read a polymorphic value back through [ArcObjectMapper].
 */
class ArcArtifactModuleDerivedTypesTest {
    @Test
    fun `a module registers every mapping it declares`() {
        val registry = ConcurrentDerivedTypeRegistry()

        ArcArtifactModuleRegistry.registerDerivedTypes(ModuleUnderTest(), registry)

        assertEquals(setOf(RegisteredShape::class.java), registry.registeredBaseTypes())
        assertEquals(RegisteredCircle::class.java, registry.resolve(RegisteredShape::class.java, "registered-circle"))
        assertEquals("registered-square", registry.idFor(RegisteredShape::class.java, RegisteredSquare::class.java))
    }

    @Test
    fun `registering the same module twice keeps the registry unchanged`() {
        val registry = ConcurrentDerivedTypeRegistry()

        ArcArtifactModuleRegistry.registerDerivedTypes(ModuleUnderTest(), registry)
        ArcArtifactModuleRegistry.registerDerivedTypes(ModuleUnderTest(), registry)

        assertEquals(RegisteredCircle::class.java, registry.resolve(RegisteredShape::class.java, "registered-circle"))
    }

    @Test
    fun `a standalone mapper reads a derived value once the module has been registered`() {
        val registry = ConcurrentDerivedTypeRegistry()
        ArcArtifactModuleRegistry.registerDerivedTypes(ModuleUnderTest(), registry)
        val mapper = ArcObjectMapper.create(registry)

        val json = mapper.writeValueAsString(RegisteredHolder(RegisteredCircle(2.0)))

        assertEquals("""{"shape":{"radius":2.0,"_derivedTypeId":"registered-circle"}}""", json)
        assertEquals(RegisteredHolder(RegisteredCircle(2.0)), mapper.readValue(json, RegisteredHolder::class.java))
    }

    @Test
    fun `an identifier the registry does not know is refused and names the base type`() {
        val registry = ConcurrentDerivedTypeRegistry()
        ArcArtifactModuleRegistry.registerDerivedTypes(ModuleUnderTest(), registry)
        val mapper = ArcObjectMapper.create(registry)

        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue("""{"shape":{"radius":2.0,"_derivedTypeId":"triangle"}}""", RegisteredHolder::class.java)
        }

        assertTrue(exception.message.orEmpty().contains("triangle"))
        assertTrue(exception.message.orEmpty().contains(RegisteredShape::class.java.name))
    }

    @Test
    fun `a value with no identifier at all is refused rather than read as null`() {
        val registry = ConcurrentDerivedTypeRegistry()
        ArcArtifactModuleRegistry.registerDerivedTypes(ModuleUnderTest(), registry)
        val mapper = ArcObjectMapper.create(registry)

        val exception = assertThrows(DatabindException::class.java) {
            mapper.readValue("""{"shape":{"radius":2.0}}""", RegisteredHolder::class.java)
        }

        assertTrue(exception.message.orEmpty().contains("_derivedTypeId"))
        assertTrue(exception.message.orEmpty().contains(RegisteredShape::class.java.name))
    }

    @Test
    fun `a registrar contributes a hierarchy no module carried`() {
        val registry = ConcurrentDerivedTypeRegistry()
        ArcArtifactModuleRegistry.registerDerivedTypes(ModuleUnderTest(), registry)
        val registrar = DerivedTypeRegistrar { target ->
            target.register(BinaryContract::class.java, BinaryImplementation::class.java)
        }

        registrar.registerDerivedTypes(registry)

        assertEquals(
            BinaryImplementation::class.java,
            registry.resolve(BinaryContract::class.java, "binary-implementation")
        )
        assertEquals(RegisteredCircle::class.java, registry.resolve(RegisteredShape::class.java, "registered-circle"))
    }
}

private class ModuleUnderTest : ArcArtifactModule(
    commandHandlers = emptyList(),
    queryPerformers = emptyList(),
    derivedTypes = listOf(
        DerivedTypeRegistration(RegisteredShape::class.java, RegisteredCircle::class.java),
        DerivedTypeRegistration(RegisteredShape::class.java, RegisteredSquare::class.java)
    )
)

internal interface RegisteredShape

@DerivedType("registered-circle")
internal data class RegisteredCircle(val radius: Double) : RegisteredShape

@DerivedType("registered-square")
internal data class RegisteredSquare(val side: Double) : RegisteredShape

internal data class RegisteredHolder(val shape: RegisteredShape)

internal interface BinaryContract

@DerivedType("binary-implementation")
internal data class BinaryImplementation(val label: String) : BinaryContract
