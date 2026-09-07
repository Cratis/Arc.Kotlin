// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DocumentationSummariesTest {
    @Test
    fun `an absent summary is always valid`() {
        assertNull(DocumentationSummaries.validate(null, "sample.Fixture"))
    }

    @Test
    fun `a single line within the limit is returned unchanged`() {
        val summary = "Creates a fixture."

        assertEquals(summary, DocumentationSummaries.validate(summary, "sample.Fixture"))
        assertEquals(
            "a".repeat(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS),
            DocumentationSummaries.validate("a".repeat(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS), "x")
        )
        val emoji = "🚀".repeat(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS)
        assertEquals(emoji, DocumentationSummaries.validate(emoji, "x"))
    }

    @Test
    fun `a blank surrounded multiline or oversized summary is rejected with the owner named`() {
        listOf(
            "" to "absent rather than blank",
            "   " to "absent rather than blank",
            " leading" to "leading or trailing whitespace",
            "trailing " to "leading or trailing whitespace",
            "two\nlines" to "single line without control characters",
            "two\rlines" to "single line without control characters",
            "next\u0085line" to "single line without control characters",
            "line\u2028separator" to "single line without control characters",
            "paragraph\u2029separator" to "single line without control characters",
            "be\u0007ll" to "single line without control characters",
            "a".repeat(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS + 1) to "at most 512 are allowed"
        ).forEach { (candidate, fragment) ->
            val exception = assertThrows(IllegalArgumentException::class.java) {
                DocumentationSummaries.validate(candidate, "sample.Fixture")
            }
            assertTrue(
                exception.message.orEmpty().contains(fragment),
                "Expected '$fragment' in '${exception.message}' for '$candidate'"
            )
            assertTrue(exception.message.orEmpty().contains("sample.Fixture"))
        }
    }

    @Test
    fun `every descriptor enforces the summary invariant`() {
        listOf<(String) -> Any>(
            { summary -> documentedCommand("Documented property.", summary) },
            { summary -> documentedQuery("Documented parameter.", summary) },
            { summary -> documentedType(summary) },
            { summary -> documentedInterface(summary) },
            { summary -> documentedEnum(summary) },
            { summary -> documentedProperty(summary) },
            { summary -> documentedParameter(summary) }
        ).forEach { create ->
            assertThrows(IllegalArgumentException::class.java) { create("two\nlines") }
        }
    }

    @Test
    fun `a manifest without summaries serializes exactly as it did before summaries existed`() {
        val json = ArcObjectMapper.create().writeValueAsString(undocumentedManifest())

        assertFalse(json.contains("summary"), "An undocumented manifest must not gain a summary field: $json")
    }

    @Test
    fun `documented metadata round trips through the manifest wire format`() {
        val mapper = ArcObjectMapper.create()
        val manifest = ArcArtifactManifest(
            "Documented",
            commands = listOf(documentedCommand("Documented property.", "Documented command.")),
            queries = listOf(documentedQuery("Documented parameter.", "Documented query.")),
            types = listOf(documentedType("Documented type.")),
            enums = listOf(documentedEnum("Documented enum.")),
            interfaces = listOf(documentedInterface("Documented interface."))
        )

        val json = mapper.writeValueAsString(manifest)
        val restored = mapper.readValue(json, ArcArtifactManifest::class.java)

        assertEquals("Documented command.", restored.commands.single().summary)
        assertEquals("Documented property.", restored.commands.single().properties.single().summary)
        assertEquals("Documented query.", restored.queries.single().summary)
        assertEquals("Documented parameter.", restored.queries.single().parameters.single().summary)
        assertEquals("Documented type.", restored.types.single().summary)
        assertEquals("Documented enum.", restored.enums.single().summary)
        assertEquals("Documented interface.", restored.interfaces.single().summary)
        assertEquals(ArcArtifactManifest.CURRENT_FORMAT_VERSION, restored.formatVersion)
    }

    @Test
    fun `an undocumented manifest survives a round trip without gaining a summary`() {
        val mapper = ArcObjectMapper.create()
        val json = mapper.writeValueAsString(undocumentedManifest())
        val restored = mapper.readValue(json, ArcArtifactManifest::class.java)

        assertNull(restored.commands.single().summary)
        assertNull(restored.commands.single().properties.single().summary)
        assertNull(restored.queries.single().summary)
        assertNull(restored.queries.single().parameters.single().summary)
        assertNull(restored.types.single().summary)
        assertNull(restored.enums.single().summary)
        assertNull(restored.interfaces.single().summary)
        assertEquals(json, mapper.writeValueAsString(restored))
    }

    private fun documentedProperty(summary: String): PropertyDescriptor = PropertyDescriptor(
        "value",
        TypeShapeDescriptor.value("kotlin.String"),
        false,
        emptyList(),
        false,
        emptyList(),
        summary
    )

    private fun documentedParameter(summary: String): ParameterDescriptor = ParameterDescriptor(
        "filter",
        TypeShapeDescriptor.value("kotlin.String"),
        QueryParameterSource.CLIENT,
        false,
        emptyList(),
        false,
        summary
    )

    private fun documentedCommand(propertySummary: String, summary: String): CommandDescriptor = CommandDescriptor(
        "Run",
        "sample.Run",
        listOf(documentedProperty(propertySummary)),
        RouteOptions(),
        listOf("sample"),
        AuthorizationMetadata(),
        null,
        false,
        null,
        false,
        emptyList(),
        summary
    )

    private fun documentedQuery(parameterSummary: String, summary: String): QueryDescriptor = QueryDescriptor(
        "find",
        "sample.Queries",
        TypeShapeDescriptor.value("kotlin.String"),
        listOf(documentedParameter(parameterSummary)),
        RouteOptions(),
        "sample.Queries.find",
        listOf("sample"),
        AuthorizationMetadata(),
        null,
        QueryHttpMethodType.AUTO,
        QueryTransportType.REQUEST_RESPONSE,
        false,
        false,
        false,
        summary
    )

    private fun documentedType(summary: String): TypeDescriptor =
        TypeDescriptor("Model", "sample.Model", listOf("sample"), emptyList(), null, null, summary)

    private fun documentedInterface(summary: String): InterfaceDescriptor =
        InterfaceDescriptor("Contract", "sample.Contract", listOf("sample"), emptyList(), summary)

    private fun documentedEnum(summary: String): EnumDescriptor =
        EnumDescriptor("State", "sample.State", listOf("sample"), emptyList(), false, summary)

    private fun undocumentedManifest(): ArcArtifactManifest {
        val shape = TypeShapeDescriptor.value("kotlin.String")
        return ArcArtifactManifest(
            "Undocumented",
            commands = listOf(
                CommandDescriptor(
                    "Run",
                    "sample.Run",
                    properties = listOf(PropertyDescriptor("value", shape))
                )
            ),
            queries = listOf(
                QueryDescriptor(
                    "find",
                    "sample.Queries",
                    shape,
                    parameters = listOf(
                        ParameterDescriptor("filter", shape, QueryParameterSource.CLIENT, false)
                    )
                )
            ),
            types = listOf(TypeDescriptor("Model", "sample.Model")),
            enums = listOf(EnumDescriptor("State", "sample.State")),
            interfaces = listOf(InterfaceDescriptor("Contract", "sample.Contract"))
        )
    }
}
