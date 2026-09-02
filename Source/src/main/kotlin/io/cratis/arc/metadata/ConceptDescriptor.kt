// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

/** Immutable metadata describing a strongly typed concept and its scalar wire type. */
public data class ConceptDescriptor(
    /** Source name of the concept. */
    public val name: String,
    /** Fully qualified source name of the concept. */
    public val fullyQualifiedName: String,
    /** Fully qualified source name of the value carried by the concept. */
    public val underlyingTypeName: String
)
