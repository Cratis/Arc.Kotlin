// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonInclude

/** Immutable language-neutral metadata for a serializable interface contract. */
public class InterfaceDescriptor @JvmOverloads constructor(
    /** Source name of the interface. */
    public val name: String,
    /** Fully qualified source name of the interface. */
    public val fullyQualifiedName: String,
    /** Stable package segments locating the interface. */
    location: List<String> = fullyQualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
    /** Serializable properties declared by the interface in source declaration order. */
    properties: List<PropertyDescriptor> = emptyList(),
    /** Single-line source documentation summary, or `null` when the interface carries none. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL) public val summary: String? = null
) {
    public val location: List<String> = java.util.List.copyOf(location)
    public val properties: List<PropertyDescriptor> = java.util.List.copyOf(properties)

    init {
        DocumentationSummaries.validate(summary, fullyQualifiedName)
    }
}
