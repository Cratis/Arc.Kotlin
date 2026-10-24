// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import io.cratis.fundamentals.strings.toCamelCase

/** Arc's nullable compatibility wrapper for Fundamentals' ASCII camel-case conversion. */
@Deprecated("Use io.cratis.fundamentals.strings.toCamelCase.", ReplaceWith("name?.toCamelCase()", "io.cratis.fundamentals.strings.toCamelCase"))
public object ArcCamelCase {
    /** Convert a member name using the ASCII acronym rules used by Cratis proxy generation. */
    @JvmStatic
    public fun convert(name: String?): String? = name?.toCamelCase()
}
