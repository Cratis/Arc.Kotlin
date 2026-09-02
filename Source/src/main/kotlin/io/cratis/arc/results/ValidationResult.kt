// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

/** Immutable validation feedback carried by command and query results. */
public class ValidationResult @JvmOverloads constructor(
    /** Severity of this result. */
    public val severity: ValidationResultSeverity,
    /** Human-readable message safe to return to the caller. */
    public val message: String,
    members: List<String> = emptyList(),
    /** Optional application-defined state. */
    public val state: Any? = null,
    /** Open string reason code. Authored validation rules use `rule` by default. */
    public val reason: String = ValidationResultReasons.RULE,
    /** Optional detail associated with [reason]. */
    public val reasonDetail: String? = null
) {
    /** Member names to which this result applies. */
    public val members: List<String> = java.util.List.copyOf(members)
}
