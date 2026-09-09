// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactModuleRegistry
import io.cratis.arc.contracts.fixtures.FixtureCircle
import io.cratis.arc.contracts.fixtures.FixtureRectangle
import io.cratis.arc.contracts.fixtures.FixtureShape
import io.cratis.arc.contracts.fixtures.FixtureShapeBase
import io.cratis.arc.contracts.fixtures.JavaFixtureContract
import io.cratis.arc.contracts.fixtures.JavaFixtureImplementation
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedTypeRegistration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The generated module carries the derived types it described, and a registry built from it reads them back.
 *
 * Registrations are emitted as real class references, so an interface base, an abstract class base, and a Java
 * interface implemented by a Java record all resolve without any classpath scanning.
 */
internal class GeneratedDerivedTypeRegistrationsTest {
    private val module = ContractTestsArcArtifactModule()

    @Test
    fun `the generated module declares every base and derivative it described`() {
        assertEquals(
            listOf(
                DerivedTypeRegistration(FixtureShape::class.java, FixtureCircle::class.java),
                DerivedTypeRegistration(FixtureShape::class.java, FixtureRectangle::class.java),
                DerivedTypeRegistration(FixtureShapeBase::class.java, FixtureCircle::class.java),
                DerivedTypeRegistration(FixtureShapeBase::class.java, FixtureRectangle::class.java),
                DerivedTypeRegistration(JavaFixtureContract::class.java, JavaFixtureImplementation::class.java)
            ),
            module.derivedTypes
        )
    }

    @Test
    fun `a mapper built from the generated module reads a Kotlin derivative back`() {
        val mapper = ArcObjectMapper.create(registryFromGeneratedModule())
        val circle = FixtureCircle("outer", 2.0, Instant.parse("2025-01-02T03:04:05Z"))

        val json = mapper.writeValueAsString(circle)

        assertEquals(circle, mapper.readValue(json, FixtureShape::class.java))
        assertEquals(circle, mapper.readValue(json, FixtureShapeBase::class.java))
    }

    @Test
    fun `a mapper built from the generated module reads a Java record derivative back`() {
        val mapper = ArcObjectMapper.create(registryFromGeneratedModule())
        val implementation = JavaFixtureImplementation("contract")

        val json = mapper.writeValueAsString(implementation)

        assertEquals(implementation, mapper.readValue(json, JavaFixtureContract::class.java))
    }

    private fun registryFromGeneratedModule() = ConcurrentDerivedTypeRegistry().also { registry ->
        ArcArtifactModuleRegistry.registerDerivedTypes(module, registry)
    }
}
