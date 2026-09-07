// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedTypeRegistrar
import io.cratis.arc.polymorphism.DerivedTypeRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** Wiring for the registry Arc's Jackson module reads derived types from. */
internal class ArcDerivedTypeRegistryAutoConfigurationTests {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(JacksonAutoConfiguration::class.java, ArcAutoConfiguration::class.java)
        )

    @Test
    fun `the registry is populated from the generated artifact modules`() {
        runner
            .withBean(ArcArtifactModule::class.java, { DerivedTypeFixtureArcArtifactModule() })
            .run { context ->
                val registry = context.getBean(DerivedTypeRegistry::class.java)
                assertEquals(
                    HostedCircle::class.java,
                    registry.resolve(HostedShape::class.java, "circle")
                )
                assertEquals(setOf(HostedShape::class.java), registry.registeredBaseTypes())
            }
    }

    @Test
    fun `application registrars contribute after the modules`() {
        runner
            .withBean(ArcArtifactModule::class.java, { DerivedTypeFixtureArcArtifactModule() })
            .withBean(
                DerivedTypeRegistrar::class.java,
                {
                    DerivedTypeRegistrar { registry ->
                        registry.register(HostedShape::class.java, HostedPolygon::class.java)
                    }
                }
            )
            .run { context ->
                val registry = context.getBean(DerivedTypeRegistry::class.java)
                assertEquals(HostedPolygon::class.java, registry.resolve(HostedShape::class.java, "polygon"))
                assertEquals(HostedCircle::class.java, registry.resolve(HostedShape::class.java, "circle"))
            }
    }

    @Test
    fun `the registry backs off for an application supplied implementation`() {
        val supplied = ConcurrentDerivedTypeRegistry()
        runner
            .withBean(ArcArtifactModule::class.java, { DerivedTypeFixtureArcArtifactModule() })
            .withBean(DerivedTypeRegistry::class.java, { supplied })
            .run { context ->
                assertSame(supplied, context.getBean(DerivedTypeRegistry::class.java))
                assertEquals(emptySet<Class<*>>(), supplied.registeredBaseTypes())
            }
    }
}
