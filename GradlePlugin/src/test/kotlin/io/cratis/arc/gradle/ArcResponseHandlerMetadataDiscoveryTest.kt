// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvFileSource

internal class ArcResponseHandlerMetadataDiscoveryTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest(name = "module name [{0}] accepted={1}")
    @CsvFileSource(resources = ["/response-handler-module-names.csv"])
    fun `module names match the shared compiler contract`(module: String, accepted: Boolean) {
        val artifact = jar("module.jar", listOf(module to document(module)))
        if (accepted) {
            val expected = "{\"formatVersion\":1,\"modules\":[${document(module)}]}\n"
            assertEquals(expected, String(ArcResponseHandlerMetadataDiscovery.extract(listOf(artifact)), Charsets.UTF_8))
        } else {
            val failure = assertThrows(GradleException::class.java) { ArcResponseHandlerMetadataDiscovery.extract(listOf(artifact)) }
            assertTrue(failure.message.orEmpty().contains("moduleName must be a safe identifier"), failure.message)
        }
    }

    @Test
    fun `artifact and jar entry order canonicalize and artifact namespace stays separate`() {
        val a = jar("a.jar", listOf("B" to document("B"), "A" to document("A")))
        val b = jar("b.jar", listOf("A" to document("A"), "B" to document("B")))
        val expected = "{\"formatVersion\":1,\"modules\":[${document("A")},${document("B")}]}\n"
        assertEquals(expected, String(ArcResponseHandlerMetadataDiscovery.extract(listOf(a)), Charsets.UTF_8))
        assertArrayEquals(ArcResponseHandlerMetadataDiscovery.extract(listOf(a, b)), ArcResponseHandlerMetadataDiscovery.extract(listOf(b, a)))
        assertTrue(ArcManifestDiscovery.discover(listOf(a, b)).isEmpty())
    }

    @Test
    fun `invalid shapes versions duplicate fields names and conflicts fail closed`() {
        val valid = document("A")
        val mutations = listOf(
            valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            valid.replace("\"p.Value\"", "\"p.Value\",\"p.Value\""),
            valid.replace("\"p.Value\"", "null"),
            valid.replace("\"p.Value\"", "\"p.Value[]\""),
            valid.replace("\"p.Value\"", "42"),
            valid.replace("\"handlers\":[", "\"unknown\":true,\"handlers\":["),
            valid + "{}"
        )
        mutations.forEachIndexed { index, mutation ->
            val bad = jar("bad$index.jar", listOf("A" to mutation))
            assertThrows(GradleException::class.java) { ArcResponseHandlerMetadataDiscovery.extract(listOf(bad)) }
        }
        val a = jar("original.jar", listOf("A" to valid))
        val changed = jar("changed.jar", listOf("A" to valid.replace("p.Value", "p.Other")))
        val conflict = assertThrows(GradleException::class.java) { ArcResponseHandlerMetadataDiscovery.extract(listOf(changed, a)) }
        assertTrue(conflict.message.orEmpty().contains("conflicting module"))
        val other = jar("other.jar", listOf("B" to document("B").replace("p.Value", "p.Other")))
        assertThrows(GradleException::class.java) { ArcResponseHandlerMetadataDiscovery.extract(listOf(a, other)) }
    }

    private fun document(module: String): String = "{\"formatVersion\":1,\"moduleName\":\"$module\",\"handlers\":[{\"handlerTypeName\":\"p.Handler\",\"handledTypeNames\":[\"p.Value\"]}]}"

    private fun jar(name: String, entries: List<Pair<String, String>>) = directory.resolve(name).toFile().also { file ->
        JarOutputStream(file.outputStream()).use { jar ->
            entries.forEach { (module, document) ->
                jar.putNextEntry(JarEntry("META-INF/cratis/arc-response-handlers/$module.json"))
                jar.write(document.toByteArray(Charsets.UTF_8))
                jar.closeEntry()
            }
        }
    }
}
