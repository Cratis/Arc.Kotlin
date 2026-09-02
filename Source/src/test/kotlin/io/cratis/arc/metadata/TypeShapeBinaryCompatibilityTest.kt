// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TypeShapeBinaryCompatibilityTest {
    @Test
    fun `all legacy property and parameter JVM constructors remain available`() {
        assertOverloadPrefixes(
            PropertyDescriptor::class.java,
            listOf(
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                String::class.java,
                List::class.java,
                java.lang.Boolean.TYPE,
                List::class.java
            ),
            2
        )
        assertKotlinDefaultConstructor(
            PropertyDescriptor::class.java,
            listOf(
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                String::class.java,
                List::class.java,
                java.lang.Boolean.TYPE,
                List::class.java
            )
        )

        assertOverloadPrefixes(
            ParameterDescriptor::class.java,
            listOf(
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                String::class.java,
                List::class.java,
                java.lang.Boolean.TYPE
            ),
            2
        )
        assertKotlinDefaultConstructor(
            ParameterDescriptor::class.java,
            listOf(
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                java.lang.Boolean.TYPE,
                String::class.java,
                List::class.java,
                java.lang.Boolean.TYPE
            )
        )

        val legacyShapeTypes = listOf(
            String::class.java,
            TypeShapeDescriptor::class.java,
            java.lang.Boolean.TYPE,
            List::class.java,
            java.lang.Boolean.TYPE
        )
        assertOverloadPrefixes(ParameterDescriptor::class.java, legacyShapeTypes, 2)
        assertKotlinDefaultConstructor(ParameterDescriptor::class.java, legacyShapeTypes)

        val legacySourceTypes = listOf(
            String::class.java,
            TypeShapeDescriptor::class.java,
            QueryParameterSource::class.java,
            List::class.java,
            java.lang.Boolean.TYPE
        )
        assertOverloadPrefixes(ParameterDescriptor::class.java, legacySourceTypes, 3)
        assertKotlinDefaultConstructor(ParameterDescriptor::class.java, legacySourceTypes)

        val canonicalDefaultTypes = listOf(
            String::class.java,
            TypeShapeDescriptor::class.java,
            QueryParameterSource::class.java,
            java.lang.Boolean.TYPE,
            List::class.java,
            java.lang.Boolean.TYPE
        )
        assertOverloadPrefixes(ParameterDescriptor::class.java, canonicalDefaultTypes, 4)
        assertKotlinDefaultConstructor(ParameterDescriptor::class.java, canonicalDefaultTypes)
        assertNotNull(ParameterDescriptor::class.java.getMethod("getHasDefault"))

        ParameterDescriptor::class.java.getConstructor(
            String::class.java,
            String::class.java,
            java.lang.Boolean::class.java,
            java.lang.Boolean::class.java,
            java.lang.Boolean::class.java,
            String::class.java,
            List::class.java,
            java.lang.Boolean::class.java,
            TypeShapeDescriptor::class.java
        )
        ParameterDescriptor::class.java.getConstructor(
            String::class.java,
            String::class.java,
            java.lang.Boolean::class.java,
            java.lang.Boolean::class.java,
            java.lang.Boolean::class.java,
            String::class.java,
            List::class.java,
            java.lang.Boolean::class.java,
            TypeShapeDescriptor::class.java,
            QueryParameterSource::class.java
        )
    }

    @Test
    fun `legacy command response data class ABI remains available`() {
        val legacyTypes = arrayOf(
            String::class.java,
            java.lang.Boolean.TYPE,
            CommandResponseValueDisposition::class.java
        )
        CommandResponseValueDescriptor::class.java.getConstructor(*legacyTypes)
        assertNotNull(CommandResponseValueDescriptor::class.java.getMethod("component1"))
        assertNotNull(CommandResponseValueDescriptor::class.java.getMethod("component2"))
        assertNotNull(CommandResponseValueDescriptor::class.java.getMethod("component3"))
        assertNotNull(CommandResponseValueDescriptor::class.java.getMethod("copy", *legacyTypes))
        assertNotNull(
            CommandResponseValueDescriptor::class.java.getDeclaredMethod(
                "copy\$default",
                CommandResponseValueDescriptor::class.java,
                *legacyTypes,
                java.lang.Integer.TYPE,
                Any::class.java
            )
        )
    }

    @Test
    fun `all legacy query JVM constructors remain available`() {
        val legacyTypes = listOf(
            String::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            RouteOptions::class.java,
            String::class.java,
            List::class.java,
            AuthorizationMetadata::class.java,
            String::class.java,
            QueryHttpMethodType::class.java,
            QueryTransportType::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE
        )
        assertOverloadPrefixes(QueryDescriptor::class.java, legacyTypes, 3)
        assertKotlinDefaultConstructor(QueryDescriptor::class.java, legacyTypes)
    }

    private fun assertOverloadPrefixes(type: Class<*>, parameterTypes: List<Class<*>>, minimum: Int) {
        (minimum..parameterTypes.size).forEach { count ->
            type.getConstructor(*parameterTypes.take(count).toTypedArray())
        }
    }

    private fun assertKotlinDefaultConstructor(type: Class<*>, parameterTypes: List<Class<*>>) {
        val constructor = type.getDeclaredConstructor(
            *(parameterTypes +
                java.lang.Integer.TYPE +
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")).toTypedArray()
        )
        assertTrue(constructor.isSynthetic)
    }
}
