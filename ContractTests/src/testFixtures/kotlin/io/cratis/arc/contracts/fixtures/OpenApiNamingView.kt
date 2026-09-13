// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.ReadModel

/** Source names that exercise Arc's default wire naming without custom Jackson annotations. */
@ReadModel
public data class OpenApiNamingView(
    public val Title: String,
    public val OptionalTitle: String?,
    public val URLValue: String
) {
    public companion object {
        public fun namedSchema(): OpenApiNamingView = OpenApiNamingView("title", null, "url")
    }
}
