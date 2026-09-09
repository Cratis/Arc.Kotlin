// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.metadata.DocumentationSummaries
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DocumentationSummaryParserTest {
    @Test
    fun `absent and blank comments carry no documentation`() {
        listOf(null, "", "   ", "\n\n", "*\n*\n", "/** */").forEach { candidate ->
            val parsed = DocumentationSummaryParser.parse(candidate)
            assertNull(parsed.summary, "Expected no summary for '$candidate'")
            assertTrue((parsed.propertyTags + parsed.paramTags).isEmpty())
        }
    }

    @Test
    fun `first paragraph folds onto one line and later paragraphs are dropped`() {
        val parsed = DocumentationSummaryParser.parse(
            "\n Creates a fixture\n over two source lines.\n\n Second paragraph is not a summary.\n"
        )

        assertEquals("Creates a fixture over two source lines.", parsed.summary)
    }

    @Test
    fun `asterisk decoration and comment delimiters are stripped`() {
        val parsed = DocumentationSummaryParser.parse("/**\n * Decorated summary.\n */")

        assertEquals("Decorated summary.", parsed.summary)
    }

    @Test
    fun `block tags end the summary and are never part of it`() {
        val parsed = DocumentationSummaryParser.parse("\n Summary text.\n @param value Ignored by the summary.\n")

        assertEquals("Summary text.", parsed.summary)
        assertEquals(mapOf("value" to "Ignored by the summary."), parsed.paramTags)
    }

    @Test
    fun `Kotlin property and Java param tags provide member documentation across continuation lines`() {
        val parsed = DocumentationSummaryParser.parse(
            """
            |
            | Command summary.
            |
            | @property first The first value
            |   continued on the next line.
            | @param second The second value.
            | @return ignored
            | @property first A repeat that must not win.
            """.trimMargin()
        )

        assertEquals("Command summary.", parsed.summary)
        assertEquals("The first value continued on the next line.", parsed.propertyTags["first"])
        assertEquals("The second value.", parsed.paramTags["second"])
        assertEquals(setOf("first"), parsed.propertyTags.keys)
        assertEquals(setOf("second"), parsed.paramTags.keys)
    }

    @Test
    fun `property and param tags are kept apart so a Kotlin class param never documents a property`() {
        val parsed = DocumentationSummaryParser.parse(
            "\n Summary.\n @param value Constructor parameter documentation.\n @property other Property documentation.\n"
        )

        assertEquals(mapOf("value" to "Constructor parameter documentation."), parsed.paramTags)
        assertEquals(mapOf("other" to "Property documentation."), parsed.propertyTags)
    }

    @Test
    fun `a tag without a name or with an unknown tag contributes nothing`() {
        val parsed = DocumentationSummaryParser.parse("\n Summary.\n @param\n @since 1.0\n @\n")

        assertEquals("Summary.", parsed.summary)
        assertTrue((parsed.propertyTags + parsed.paramTags).isEmpty())
    }

    @Test
    fun `a leading fenced code block is omitted from the summary`() {
        val parsed = DocumentationSummaryParser.parse(
            "\n ```kotlin\n val ignored = 1\n ```\n Real summary after the fence.\n"
        )

        assertEquals("Real summary after the fence.", parsed.summary)
    }

    @Test
    fun `a leading tilde fence and an unterminated fence are both omitted`() {
        assertEquals(
            "Summary after tildes.",
            DocumentationSummaryParser.parse("\n ~~~\n ignored\n ~~~\n Summary after tildes.\n").summary
        )
        assertNull(DocumentationSummaryParser.parse("\n ```\n everything after is code\n more code\n").summary)
    }

    @Test
    fun `leading space indented and tab indented code blocks are omitted`() {
        assertEquals(
            "Summary after indented code.",
            DocumentationSummaryParser.parse("\n     indented code line\n Summary after indented code.\n").summary
        )
        assertEquals(
            "Summary after tab code.",
            DocumentationSummaryParser.parse("\n \tindented with a tab\n Summary after tab code.\n").summary
        )
    }

    @Test
    fun `carriage returns and Unicode line controls separate lines exactly like a line feed`() {
        listOf("\r\n", "\r", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029").forEach { terminator ->
            val comment = " First" + terminator + " second." + terminator + terminator + "@param a b"
            val parsed = DocumentationSummaryParser.parse(comment)

            assertEquals("First second.", parsed.summary, "Failed for U+%04X".format(terminator.last().code))
            assertEquals("b", parsed.paramTags["a"])
        }
    }

    @Test
    fun `control characters are dropped rather than carried into metadata`() {
        val parsed = DocumentationSummaryParser.parse(" Summary \u0007 with \u0001 controls.")

        assertEquals("Summary with controls.", parsed.summary)
        assertEquals(parsed.summary, DocumentationSummaries.validate(parsed.summary, "test"))
    }

    @Test
    fun `every produced summary satisfies the metadata invariant`() {
        val parsed = DocumentationSummaryParser.parse(
            "\n Summary with  collapsed   whitespace \t and a trailing space \n @property p  spaced  value \n"
        )

        assertEquals("Summary with collapsed whitespace and a trailing space", parsed.summary)
        assertEquals("spaced value", parsed.propertyTags["p"])
        assertEquals(parsed.summary, DocumentationSummaries.validate(parsed.summary, "test"))
        assertEquals(
            parsed.propertyTags["p"],
            DocumentationSummaries.validate(parsed.propertyTags["p"], "test")
        )
    }

    @Test
    fun `summaries stop at the code point limit without splitting a surrogate pair`() {
        val emoji = "🚀"
        val parsed = DocumentationSummaryParser.parse(" " + emoji.repeat(1_000))
        val summary = requireNotNull(parsed.summary)

        assertEquals(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS, summary.codePointCount(0, summary.length))
        assertEquals(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS * 2, summary.length)
        assertEquals(summary, DocumentationSummaries.validate(summary, "test"))
    }

    @Test
    fun `a long ASCII summary is truncated to the code point limit`() {
        val parsed = DocumentationSummaryParser.parse(" " + "ab ".repeat(1_000))
        val summary = requireNotNull(parsed.summary)

        assertEquals(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS, summary.length)
        assertEquals(summary, DocumentationSummaries.validate(summary, "test"))
    }

    @Test
    fun `pathological comments stay bounded in size and linear in time`() {
        val size = 2_000_000
        val pathological = mapOf(
            "one enormous token" to "a".repeat(size),
            "only whitespace" to " ".repeat(size),
            "only asterisks" to "*".repeat(size),
            "asterisk slash pairs" to "*/".repeat(size / 2),
            "many empty lines" to "\n".repeat(size),
            "many decorated lines" to " * a\n".repeat(size / 5),
            "unterminated fence" to " ```\n" + " code\n".repeat(size / 6),
            "many tags" to " Summary.\n" + (0 until size / 14).joinToString("\n") { " @param p$it v" }
        )

        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            pathological.forEach { (name, comment) ->
                val parsed = DocumentationSummaryParser.parse(comment)
                val summary = parsed.summary
                if (summary != null) {
                    assertTrue(
                        summary.codePointCount(0, summary.length) <=
                            DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS,
                        "'$name' produced an unbounded summary"
                    )
                    assertEquals(summary, DocumentationSummaries.validate(summary, name))
                }
                (parsed.propertyTags + parsed.paramTags).forEach { (member, value) ->
                    assertEquals(value, DocumentationSummaries.validate(value, member))
                }
            }
        }
    }
}
