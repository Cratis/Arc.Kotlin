// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
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
    // Constructor-scoped mutation keeps the original primary constructor's exact JVM descriptor, including the
    // synthetic bridge that Kotlin default arguments compile against.
    private var summaryBacking: String? = null

    public val location: List<String> = java.util.List.copyOf(location)
    public val members: List<EnumMemberDescriptor> = java.util.List.copyOf(members)

    /**
     * Creates enum metadata carrying a single-line source documentation summary.
     *
     * Enum members are deliberately undocumented: Arc collects a summary for the declaration only.
     */
    @JsonCreator
    public constructor(
        @JsonProperty("name") name: String,
        @JsonProperty("fullyQualifiedName") fullyQualifiedName: String,
        @JsonProperty("location") location: List<String>?,
        @JsonProperty("members") members: List<EnumMemberDescriptor>?,
        @JsonProperty("isFlags") isFlags: Boolean?,
        @JsonProperty("summary") summary: String?
    ) : this(
        name,
        fullyQualifiedName,
        location ?: fullyQualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        members.orEmpty(),
        isFlags ?: false
    ) {
        summaryBacking = DocumentationSummaries.validate(summary, fullyQualifiedName)
    }

    /** Single-line source documentation summary, or `null` when the enum carries none. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    public val summary: String?
        get() = summaryBacking

    /** Expression used by the .NET FlagsEnum template to initialize `all<Name>`. */
    public val allFlagsExpression: String = if (isFlags) {
        members.filter { member -> member.value != 0 }
            .joinToString(" | ") { member -> "$name.${ArcCamelCase.convert(member.name)}" }
    } else {
        ""
    }
}
