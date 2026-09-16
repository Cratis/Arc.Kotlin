// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource

internal class ResponseHandlerMetadataTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest(name = "module name [{0}] accepted={1}")
    @CsvFileSource(resources = ["/response-handler-module-names.csv"])
    fun `module names match the shared compiler contract`(module: String, accepted: Boolean) {
        assertEquals(if (accepted) module else null, validateModuleName(module))
        if (accepted) {
            val declarations = read(document(module, "p.Handler"))
            assertEquals(listOf("p.Handler"), declarations.map { it.handler })
            assertEquals("${ResponseHandlerMetadata.PREFIX}$module.json", declarations.single().resource)
        } else {
            val failure = assertThrows(IllegalArgumentException::class.java) { read(document(module, "p.Handler")) }
            assertEquals("moduleName must be a safe module identifier", failure.message)
        }
    }

    @Test
    fun `index order canonicalizes identical modules and distinct handlers may share a value`() {
        val a = document("A", "p.First")
        val b = document("B", "p.Second")
        assertEquals(read("$a,$b"), read("$b,$a,$a"))
        assertEquals(listOf("p.First", "p.Second"), read("$b,$a").map { it.handler })
        assertEquals(emptyList<ResponseHandlerMetadata.Declaration>(), read(""))
        val shared = document("B", "p.First")
        assertEquals(read("$a,$shared"), read("$shared,$a"), "Duplicate claim diagnostics must name the same canonical resource")
    }

    @Test
    fun `invalid versions types duplicates unknown fields module and handler conflicts fail closed`() {
        val a = document("A", "p.Handler")
        listOf(
            a.replace("\"formatVersion\":1", "\"formatVersion\":0"),
            a.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            a.replace("\"p.Value\"", "false"),
            a.replace("\"p.Value\"", "\"p.Value\",\"p.Value\""),
            a.replace("\"p.Value\"", "\"p.Value[]\""),
            a.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            a.replace("\"handlers\":[", "\"unknown\":null,\"handlers\":["),
            "$a,${a.replace("p.Value", "p.Other")}",
            "$a,${document("B", "p.Handler").replace("p.Value", "p.Other")}",
            a.replace("\"moduleName\":\"A\"", "\"moduleName\":\"class\"")
        ).forEach { malformed -> assertThrows(Exception::class.java) { read(malformed) } }
        assertThrows(Exception::class.java) { ResponseHandlerMetadata.read("relative.json") }
        assertThrows(Exception::class.java) { ResponseHandlerMetadata.read("https://example.invalid/index.json") }
        assertThrows(Exception::class.java) { ResponseHandlerMetadata.read(directory.resolve("absent.json").toUri().toString()) }
    }

    private fun read(modules: String): List<ResponseHandlerMetadata.Declaration> {
        val file = directory.resolve("index with spaces.json").toFile()
        file.writeText("{\"formatVersion\":1,\"modules\":[$modules]}")
        return ResponseHandlerMetadata.read(file.toURI().toASCIIString())
    }

    private fun document(module: String, handler: String): String =
        "{\"formatVersion\":1,\"moduleName\":\"$module\",\"handlers\":[{\"handlerTypeName\":\"$handler\",\"handledTypeNames\":[\"p.Value\"]}]}"
}
