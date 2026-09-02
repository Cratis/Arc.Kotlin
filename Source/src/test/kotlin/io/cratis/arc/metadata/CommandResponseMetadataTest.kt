// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.HandlesCommandResponseValues
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.results.CommandResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.reflect.full.findAnnotation

internal class CommandResponseMetadataTest {
    @Test
    fun `descriptor defaults preserve commands without responses`() {
        val descriptor = CommandDescriptor("Run", "tests.Run")

        assertEquals(emptyList<CommandResponseValueDescriptor>(), descriptor.responseValues)
        assertNull(descriptor.responseTypeName)
        assertFalse(descriptor.responseIsEnumerable)
    }

    @Test
    fun `legacy response constructor values project to response value metadata`() {
        val descriptor = CommandDescriptor(
            "Find",
            "tests.Find",
            responseTypeName = "tests.Result",
            responseIsEnumerable = true
        )

        assertEquals(
            listOf(
                CommandResponseValueDescriptor(
                    "tests.Result",
                    true,
                    CommandResponseValueDisposition.CLIENT
                )
            ),
            descriptor.responseValues
        )
        assertEquals("tests.Result", descriptor.responseTypeName)
        assertEquals(true, descriptor.responseIsEnumerable)
    }

    @Test
    fun `ordered response value metadata is immutable and projects the client response`() {
        val handled = CommandResponseValueDescriptor(
            "tests.Event",
            true,
            CommandResponseValueDisposition.HANDLED
        )
        val client = CommandResponseValueDescriptor(
            "tests.Result",
            false,
            CommandResponseValueDisposition.CLIENT
        )
        val input = mutableListOf(handled, client)
        val descriptor = CommandDescriptor("Run", "tests.Run", responseValues = input)

        input.clear()

        assertEquals(listOf(handled, client), descriptor.responseValues)
        assertEquals("tests.Result", descriptor.responseTypeName)
        assertFalse(descriptor.responseIsEnumerable)
        assertThrows(UnsupportedOperationException::class.java) {
            (descriptor.responseValues as MutableList<CommandResponseValueDescriptor>).add(handled)
        }
    }

    @Test
    fun `descriptor rejects multiple client response values`() {
        val first = CommandResponseValueDescriptor("tests.First", false, CommandResponseValueDisposition.CLIENT)
        val second = CommandResponseValueDescriptor("tests.Second", false, CommandResponseValueDisposition.CLIENT)

        assertThrows(IllegalArgumentException::class.java) {
            CommandDescriptor("Run", "tests.Run", responseValues = listOf(first, second))
        }
    }

    @Test
    fun `descriptor rejects contradictory explicit legacy response metadata`() {
        val client = CommandResponseValueDescriptor("tests.Result", true, CommandResponseValueDisposition.CLIENT)

        assertThrows(IllegalArgumentException::class.java) {
            CommandDescriptor(
                "Run",
                "tests.Run",
                responseTypeName = "tests.OtherResult",
                responseIsEnumerable = true,
                responseValues = listOf(client)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandDescriptor(
                "Run",
                "tests.Run",
                responseTypeName = "tests.Result",
                responseIsEnumerable = false,
                responseValues = listOf(client)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandDescriptor(
                "Run",
                "tests.Run",
                responseTypeName = "tests.Result",
                responseValues = listOf(
                    CommandResponseValueDescriptor(
                        "tests.Event",
                        false,
                        CommandResponseValueDisposition.HANDLED
                    )
                )
            )
        }
    }

    @Test
    fun `Jackson round trips format 3 descriptors without response values`() {
        val mapper = ArcObjectMapper.create()
        val manifest = mapper.readValue(
            """
            {
              "formatVersion": 3,
              "moduleName": "legacy",
              "commands": [{
                "name": "Find",
                "typeName": "tests.Find",
                "responseTypeName": "tests.Result",
                "responseIsEnumerable": true
              }]
            }
            """.trimIndent(),
            ArcArtifactManifest::class.java
        )

        val descriptor = manifest.commands.single()
        assertEquals(3, manifest.formatVersion)
        assertEquals("tests.Result", descriptor.responseTypeName)
        assertEquals(true, descriptor.responseIsEnumerable)
        assertEquals(
            listOf(CommandResponseValueDescriptor("tests.Result", true, CommandResponseValueDisposition.CLIENT)),
            descriptor.responseValues
        )

        assertDescriptorRoundTrip(mapper, manifest, descriptor)
    }

    @Test
    fun `Jackson round trips format 4 descriptors with explicit response values`() {
        val mapper = ArcObjectMapper.create()
        val manifest = mapper.readValue(
            """
            {
              "formatVersion": 4,
              "moduleName": "current",
              "commands": [{
                "name": "Run",
                "typeName": "tests.Run",
                "responseValues": [
                  {"typeName": "tests.Event", "isEnumerable": true, "disposition": "HANDLED"},
                  {"typeName": "tests.Result", "isEnumerable": false, "disposition": "CLIENT"}
                ]
              }]
            }
            """.trimIndent(),
            ArcArtifactManifest::class.java
        )

        val descriptor = manifest.commands.single()
        assertEquals(4, manifest.formatVersion)
        assertEquals(
            listOf(
                CommandResponseValueDescriptor("tests.Event", true, CommandResponseValueDisposition.HANDLED),
                CommandResponseValueDescriptor("tests.Result", false, CommandResponseValueDisposition.CLIENT)
            ),
            descriptor.responseValues
        )
        assertEquals("tests.Result", descriptor.responseTypeName)
        assertFalse(descriptor.responseIsEnumerable)

        assertDescriptorRoundTrip(mapper, manifest, descriptor)
    }

    @Test
    fun `handler annotation retains ordered Kotlin classes at runtime`() {
        val annotation = AnnotatedHandler::class.findAnnotation<HandlesCommandResponseValues>()

        assertEquals(listOf(String::class, Number::class), annotation?.value?.toList())
    }

    private fun assertDescriptorRoundTrip(
        mapper: com.fasterxml.jackson.databind.ObjectMapper,
        manifest: ArcArtifactManifest,
        descriptor: CommandDescriptor
    ) {
        val roundTripped = mapper.readValue(
            mapper.writeValueAsString(manifest),
            ArcArtifactManifest::class.java
        ).commands.single()
        assertEquals(descriptor.responseValues, roundTripped.responseValues)
        assertEquals(descriptor.responseTypeName, roundTripped.responseTypeName)
        assertEquals(descriptor.responseIsEnumerable, roundTripped.responseIsEnumerable)
    }

    @HandlesCommandResponseValues(String::class, Number::class)
    private class AnnotatedHandler : CommandResponseValueHandler {
        override fun canHandle(context: CommandContext, value: Any): Boolean = value is String || value is Number

        override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
            CommandResult.success(context.correlationId)
    }
}
