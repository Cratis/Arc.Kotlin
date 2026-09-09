// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

/**
 * The single invariant for source documentation summaries carried by Arc metadata.
 *
 * A summary is the first paragraph of a Kotlin KDoc or Java Javadoc comment, reduced to exactly one line so that
 * every consumer - the artifact manifest, runtime introspection, and generated TypeScript documentation - renders it
 * without re-deciding how to fold, truncate, or escape it.
 */
public object DocumentationSummaries {
    /** Maximum number of Unicode code points a summary may carry. */
    public const val MAX_LENGTH_IN_CODE_POINTS: Int = 512

    /**
     * Validates a summary against the Arc metadata invariant and returns it unchanged.
     *
     * A `null` value means the artifact carries no documentation and is always valid.
     *
     * @param summary Summary to validate, or `null` when absent.
     * @param owner Identity used in the failure message, for example a fully qualified artifact name.
     * @return The validated summary, or `null`.
     * @throws IllegalArgumentException when the summary is blank, carries surrounding whitespace, spans more than one
     * line, contains a control character, or exceeds [MAX_LENGTH_IN_CODE_POINTS].
     */
    @JvmStatic
    public fun validate(summary: String?, owner: String): String? {
        if (summary == null) return null
        require(summary.isNotBlank()) {
            "Documentation summary for '$owner' must be absent rather than blank."
        }
        require(summary == summary.trim()) {
            "Documentation summary for '$owner' must not carry leading or trailing whitespace."
        }
        val offending = summary.indexOfFirst(::isForbidden)
        require(offending < 0) {
            "Documentation summary for '$owner' must be a single line without control characters; found " +
                "U+%04X".format(summary[offending].code) + " at index $offending."
        }
        val codePoints = summary.codePointCount(0, summary.length)
        require(codePoints <= MAX_LENGTH_IN_CODE_POINTS) {
            "Documentation summary for '$owner' carries $codePoints code points; at most " +
                "$MAX_LENGTH_IN_CODE_POINTS are allowed."
        }
        return summary
    }

    // NEXT LINE, LINE SEPARATOR, and PARAGRAPH SEPARATOR end a line for a JSDoc reader exactly as a line feed does.
    private fun isForbidden(character: Char): Boolean = when (character.code) {
        0x0085, 0x2028, 0x2029 -> true
        else -> character.code < 0x20 || character.code == 0x7F || character.code in 0x80..0x9F
    }
}
