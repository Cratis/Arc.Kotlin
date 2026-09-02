// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

/** Immutable language-neutral metadata describing one client-representable validation rule. */
public class ValidationRuleDescriptor @JvmOverloads constructor(
    /** Exact @cratis validator rule name. */
    public val ruleName: String,
    arguments: List<Any> = emptyList(),
    /** Explicit validation message, when supplied by the source annotation. */
    public val message: String? = null
) {
    /** JSON-compatible rule arguments in declaration order. */
    public val arguments: List<Any> = java.util.List.copyOf(arguments)

    override fun equals(other: Any?): Boolean = other is ValidationRuleDescriptor &&
        ruleName == other.ruleName && arguments == other.arguments && message == other.message

    override fun hashCode(): Int = 31 * (31 * ruleName.hashCode() + arguments.hashCode()) + (message?.hashCode() ?: 0)

    override fun toString(): String = "ValidationRuleDescriptor(ruleName=$ruleName, arguments=$arguments, message=$message)"
}
