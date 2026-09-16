// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.metadata.ValidationRuleDescriptor

/** Frozen, typed direct-member rules. Only the shared fluent vocabulary is accepted, not Jakarta metadata. */
public class FluentValidationMember(
    public val member: String,
    public val memberType: Class<*>,
    rules: List<ValidationRuleDescriptor>
) {
    init {
        require(member.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "Fluent member '$member' must be a direct member name." }
        require(rules.isNotEmpty()) { "Fluent member '$member' must declare at least one rule." }
    }

    /** Canonically ordered conjunction; exact rule/arguments/message duplicates run once. */
    public val rules: List<ValidationRuleDescriptor> = FluentValidationRules.snapshot(memberType, rules)

    override fun equals(other: Any?): Boolean = other is FluentValidationMember &&
        member == other.member && memberType == other.memberType && rules == other.rules

    override fun hashCode(): Int = 31 * (31 * member.hashCode() + memberType.hashCode()) + rules.hashCode()
}
