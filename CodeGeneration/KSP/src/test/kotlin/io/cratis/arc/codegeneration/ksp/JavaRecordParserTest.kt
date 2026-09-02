// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class JavaRecordParserTest {
    @Test
    fun `outer nullability is distinct from nullable generic type uses`() {
        val source = """
            package sample;

            public record Maps(
                @Nullable Map<String, String> outerNullable,
                Map<@Nullable String, String> nullableKey,
                Map<String, @Nullable String> nullableValue,
                Map<String, List<@Nullable String>> nullableListElement,
                Map<String, Map<String, @Nullable String>> nullableNestedMapValue
            ) { }
        """.trimIndent()

        val properties = requireNotNull(parseJavaRecordProperties(source, "Maps")).associateBy { it.name }

        assertEquals(true, properties.getValue("outerNullable").isNullable)
        assertEquals("Map<String, String>", properties.getValue("outerNullable").typeName)
        assertEquals(false, properties.getValue("nullableKey").isNullable)
        assertEquals("Map<@Nullable String, String>", properties.getValue("nullableKey").typeName)
        assertEquals(false, properties.getValue("nullableValue").isNullable)
        assertEquals("Map<String, @Nullable String>", properties.getValue("nullableValue").typeName)
        assertEquals("Map<String, List<@Nullable String>>", properties.getValue("nullableListElement").typeName)
        assertEquals(
            "Map<String, Map<String, @Nullable String>>",
            properties.getValue("nullableNestedMapValue").typeName
        )
    }

    @Test
    fun `Arc and optional Hibernate annotations retain exact validation identities and messages`() {
        val source = """
            package sample;

            public record Contact(
                @Phone(message = "Phone message") String phone,
                @io.cratis.arc.validation.Url(message = "URL message") String url,
                @CreditCard(message = "Card message") String card,
                @URL(message = "Hibernate URL") String hibernateUrl,
                @org.hibernate.validator.constraints.CreditCardNumber(message = "Hibernate card") String hibernateCard
            ) { }
        """.trimIndent()

        val properties = requireNotNull(parseJavaRecordProperties(source, "Contact"))

        assertEquals(
            listOf(
                "io.cratis.arc.validation.Phone" to "Phone message",
                "io.cratis.arc.validation.Url" to "URL message",
                "io.cratis.arc.validation.CreditCard" to "Card message",
                "org.hibernate.validator.constraints.URL" to "Hibernate URL",
                "org.hibernate.validator.constraints.CreditCardNumber" to "Hibernate card"
            ),
            properties.map { property ->
                val annotation = property.validationAnnotations.single()
                annotation.qualifiedName to annotation.arguments["message"]
            }
        )
    }
}
