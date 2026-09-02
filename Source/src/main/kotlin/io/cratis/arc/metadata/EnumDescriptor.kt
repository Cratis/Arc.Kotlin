// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import io.cratis.arc.json.ArcCamelCase

/** Immutable language-neutral metadata describing an enum. */
public class EnumDescriptor @JvmOverloads constructor(
    /** Source name of the enum. */
    public val name: String,
    /** Fully qualified source name of the enum. */
    public val fullyQualifiedName: String,
    /** Stable package segments locating the enum. */
    location: List<String> = fullyQualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
    /** Members in source declaration order with explicit numeric wire values. */
    members: List<EnumMemberDescriptor> = emptyList(),
    /** Whether the enum carries the JVM [io.cratis.arc.concepts.Flags] annotation. */
    public val isFlags: Boolean = false
) {
    public val location: List<String> = java.util.List.copyOf(location)
    public val members: List<EnumMemberDescriptor> = java.util.List.copyOf(members)

    /** Expression used by the .NET FlagsEnum template to initialize `all<Name>`. */
    public val allFlagsExpression: String = if (isFlags) {
        members.filter { member -> member.value != 0 }
            .joinToString(" | ") { member -> "$name.${ArcCamelCase.convert(member.name)}" }
    } else {
        ""
    }
}
