// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/** Immutable language-neutral metadata describing a serializable model type. */
public class TypeDescriptor @JvmOverloads constructor(
    /** Source name of the type. */
    public val name: String,
    /** Fully qualified source name of the type. */
    public val fullyQualifiedName: String,
    /** Stable package segments locating the type. */
    location: List<String> = fullyQualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
    /** Serializable properties in source declaration order. */
    properties: List<PropertyDescriptor> = emptyList(),
    /** Fully qualified source name of the supported base type, when present. */
    public val baseTypeName: String? = null,
    /** Stable derived-type identifier, when explicitly declared. */
    public val derivedTypeId: String? = null
) {
    // Constructor-scoped mutation keeps the original primary constructor's exact JVM descriptor, including the
    // synthetic bridge that Kotlin default arguments compile against.
    private var summaryBacking: String? = null

    public val location: List<String> = java.util.List.copyOf(location)
    public val properties: List<PropertyDescriptor> = java.util.List.copyOf(properties)

    /** Creates model metadata carrying a single-line source documentation summary. */
    @JsonCreator
    public constructor(
        @JsonProperty("name") name: String,
        @JsonProperty("fullyQualifiedName") fullyQualifiedName: String,
        @JsonProperty("location") location: List<String>?,
        @JsonProperty("properties") properties: List<PropertyDescriptor>?,
        @JsonProperty("baseTypeName") baseTypeName: String?,
        @JsonProperty("derivedTypeId") derivedTypeId: String?,
        @JsonProperty("summary") summary: String?
    ) : this(
        name,
        fullyQualifiedName,
        location ?: fullyQualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        properties.orEmpty(),
        baseTypeName,
        derivedTypeId
    ) {
        summaryBacking = DocumentationSummaries.validate(summary, fullyQualifiedName)
    }

    /** Single-line source documentation summary, or `null` when the type carries none. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    public val summary: String?
        get() = summaryBacking
}
