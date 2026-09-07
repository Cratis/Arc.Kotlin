// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** Immutable language-neutral metadata for a serializable interface contract. */
public class InterfaceDescriptor @JvmOverloads constructor(
    /** Source name of the interface. */
    public val name: String,
    /** Fully qualified source name of the interface. */
    public val fullyQualifiedName: String,
    /** Stable package segments locating the interface. */
    location: List<String> = fullyQualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
    /** Serializable properties declared by the interface in source declaration order. */
    properties: List<PropertyDescriptor> = emptyList()
) {
    // Constructor-scoped mutation keeps the original primary constructor's exact JVM descriptor, including the
    // synthetic bridge that Kotlin default arguments compile against.
    private var summaryBacking: String? = null

    public val location: List<String> = java.util.List.copyOf(location)
    public val properties: List<PropertyDescriptor> = java.util.List.copyOf(properties)

    /** Creates interface metadata carrying a single-line source documentation summary. */
    @JsonCreator
    public constructor(
        @JsonProperty("name") name: String,
        @JsonProperty("fullyQualifiedName") fullyQualifiedName: String,
        @JsonProperty("location") location: List<String>?,
        @JsonProperty("properties") properties: List<PropertyDescriptor>?,
        @JsonProperty("summary") summary: String?
    ) : this(
        name,
        fullyQualifiedName,
        location ?: fullyQualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        properties.orEmpty()
    ) {
        summaryBacking = DocumentationSummaries.validate(summary, fullyQualifiedName)
    }

    /** Single-line source documentation summary, or `null` when the interface carries none. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    public val summary: String?
        get() = summaryBacking
}
