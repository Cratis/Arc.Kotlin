// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.metadata.ValidationRuleDescriptor

/** Literal direct-member fluent chain, authored in a validator constructor. All mutations stop at freezing. */
public class FluentRuleBuilder private constructor(
    private val add: (ValidationRuleDescriptor) -> Unit,
    private val message: (String) -> Unit
) {
    internal companion object {
        @JvmSynthetic
        internal fun create(add: (ValidationRuleDescriptor) -> Unit, message: (String) -> Unit): FluentRuleBuilder =
            FluentRuleBuilder(add, message)
    }

    /** Rejects null. Other rules except notEmpty accept null. */
    public fun notNull(): FluentRuleBuilder = rule("notNull")
    /** Rejects null, ECMAScript-blank strings and empty arrays/collections. */
    public fun notEmpty(): FluentRuleBuilder = rule("notEmpty")
    /** Minimum UTF-16 string length or container element count. */
    public fun minLength(min: Int): FluentRuleBuilder = rule("minLength", min)
    /** Maximum UTF-16 string length or container element count. */
    public fun maxLength(max: Int): FluentRuleBuilder = rule("maxLength", max)
    /** Inclusive minimum and maximum length. */
    public fun length(min: Int, max: Int): FluentRuleBuilder = rule("length", min, max)
    /** Uses the client's bounded email pattern, not Jakarta's email implementation. */
    public fun emailAddress(): FluentRuleBuilder = rule("emailAddress")
    /** Accepts ASCII digits, ECMAScript whitespace and ()+-. */
    public fun phone(): FluentRuleBuilder = rule("phone")
    /** Uses the client's HTTP(S) prefix rule, not a URI parser. */
    public fun url(): FluentRuleBuilder = rule("url")
    /** Searches using a portable ASCII regex subset; unsupported constructs fail declaration. */
    public fun matches(pattern: String): FluentRuleBuilder = rule("matches", pattern)
    /** Exclusive numeric lower bound in the finite JavaScript safe number domain. */
    public fun greaterThan(value: Number): FluentRuleBuilder = rule("greaterThan", value)
    /** Inclusive numeric lower bound. */
    public fun greaterThanOrEqual(value: Number): FluentRuleBuilder = rule("greaterThanOrEqual", value)
    /** Exclusive numeric upper bound. */
    public fun lessThan(value: Number): FluentRuleBuilder = rule("lessThan", value)
    /** Inclusive numeric upper bound. */
    public fun lessThanOrEqual(value: Number): FluentRuleBuilder = rule("lessThanOrEqual", value)

    /** Replaces only the immediately preceding rule's message, not every rule in the chain. */
    public fun withMessage(value: String): FluentRuleBuilder = apply { message(value) }

    /** Deliberately unsupported: the pinned client has no credit-card rule. Use a server-only validator. */
    public fun creditCard(): FluentRuleBuilder = throw IllegalArgumentException(
        "Shared fluent creditCard is unsupported by the client; use a server-only ModelValidator instead."
    )

    private fun rule(name: String, vararg arguments: Any): FluentRuleBuilder = apply {
        add(ValidationRuleDescriptor(name, arguments.toList()))
    }
}
