// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class CommandDescriptorBinaryCompatibilityTest {
    @Test
    fun `all legacy Java overloads and the Kotlin default constructor remain available`() {
        val legacyParameterTypes = listOf(
            String::class.java,
            String::class.java,
            List::class.java,
            RouteOptions::class.java,
            List::class.java,
            AuthorizationMetadata::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            String::class.java,
            java.lang.Boolean.TYPE
        )

        (2..legacyParameterTypes.size).forEach { parameterCount ->
            CommandDescriptor::class.java.getConstructor(
                *legacyParameterTypes.take(parameterCount).toTypedArray()
            )
        }

        val defaultConstructor = CommandDescriptor::class.java.getDeclaredConstructor(
            *(legacyParameterTypes +
                java.lang.Integer.TYPE +
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")).toTypedArray()
        )
        assertTrue(defaultConstructor.isSynthetic)
    }

    @Test
    fun `handler compiled against the legacy Kotlin default constructor loads and executes`() {
        val fixtureBytes = checkNotNull(
            javaClass.getResourceAsStream(FIXTURE_RESOURCE)
        ) { "Missing legacy CommandDescriptor binary fixture" }.bufferedReader().useLines { lines ->
            Base64.getDecoder().decode(
                lines.filterNot { line -> line.startsWith("//") }.joinToString("")
            )
        }
        val fixtureClass = object : ClassLoader(CommandDescriptor::class.java.classLoader) {
            fun defineFixture(): Class<*> = defineClass(FIXTURE_CLASS_NAME, fixtureBytes, 0, fixtureBytes.size)
        }.defineFixture()

        val descriptor = fixtureClass.getMethod("create").invoke(null) as CommandDescriptor

        assertEquals("Legacy", descriptor.name)
        assertEquals("legacy.LegacyCommand", descriptor.typeName)
        assertTrue(descriptor.treatWarningsAsErrors)
        assertEquals(emptyList<CommandResponseValueDescriptor>(), descriptor.responseValues)
    }

    private companion object {
        const val FIXTURE_CLASS_NAME: String =
            "io.cratis.arc.metadata.compat.LegacyGeneratedHandlerFixture"
        const val FIXTURE_RESOURCE: String =
            "/io/cratis/arc/metadata/compat/LegacyGeneratedHandlerFixture.class.b64"
    }
}
