// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.metadata.ValidationRuleDescriptor
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class FluentValidationMetadataTest {
    private val declaration = FluentDeclaration("fixture.Rules", "fixture.Person", listOf(
        FluentMember("name", "java.lang.String", listOf(ValidationRuleDescriptor("maxLength", listOf(5), "Name \"quoted\"\nline")))
    ))

    @Test
    fun `separate versioned document round trips immutable typed rules and identical duplicates`() {
        val document = FluentValidationMetadata.document("Library", listOf(declaration))
        assertEquals(listOf(declaration), read("{\"formatVersion\":1,\"modules\":[$document,$document]}"))
    }

    @Test
    fun `conflicting index declarations and module versions reject`() {
        val document = FluentValidationMetadata.document("Library", listOf(declaration))
        assertThrows(IllegalArgumentException::class.java) { read("{\"formatVersion\":1,\"modules\":[$document,${document.replace("[5]", "[6]")}]}" ) }
        assertThrows(IllegalArgumentException::class.java) { read("{\"formatVersion\":2,\"modules\":[]}") }
        assertThrows(IllegalArgumentException::class.java) { read("{\"formatVersion\":1,\"modules\":[${document.replace("\"formatVersion\":1", "\"formatVersion\":2")}]}" ) }
    }

    @Test
    fun `index requires exact schema scalar types and representable rules`() {
        val document = FluentValidationMetadata.document("Library", listOf(declaration))
        val invalid = listOf(
            document.replace("\"memberTypeName\":\"java.lang.String\"", "\"memberTypeName\":true"),
            document.replace("\"member\":\"name\"", "\"member\":null"),
            document.replace("\"modelTypeName\"", "\"unknown\""),
            document.replace("\"maxLength\"", "\"creditCard\""),
            document.replace("[5]", "[\"5\"]"),
            document.replace("[5]", "[-1]"),
            document.replace("\"moduleName\":\"Library\"", "\"moduleName\":\"class\"")
        )
        invalid.forEach { assertThrows(Exception::class.java) { read("{\"formatVersion\":1,\"modules\":[$it]}") } }
    }

    @Test
    fun `only absolute file URI indexes are accepted`() {
        assertThrows(IllegalArgumentException::class.java) { FluentValidationMetadata.readIndex("relative.json") }
        assertThrows(IllegalArgumentException::class.java) { FluentValidationMetadata.readIndex("https://example.test/index.json") }
    }

    private fun read(content: String): List<FluentDeclaration> {
        val root = File(System.getProperty("arc.fluent.evidence") ?: System.getProperty("java.io.tmpdir")).apply { mkdirs() }
        val file = Files.createTempFile(root.toPath(), "fluent-index-", ".json").toFile().apply { writeText(content) }
        return FluentValidationMetadata.readIndex(file.toURI().toASCIIString())
    }
}
