// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

/** Immutable language-neutral metadata for one enum member. */
public data class EnumMemberDescriptor(
    /** Source name of the member. */
    public val name: String,
    /** Explicit numeric wire value. */
    public val value: Int
)
