// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

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
    public val location: List<String> = java.util.List.copyOf(location)
    public val properties: List<PropertyDescriptor> = java.util.List.copyOf(properties)
}
