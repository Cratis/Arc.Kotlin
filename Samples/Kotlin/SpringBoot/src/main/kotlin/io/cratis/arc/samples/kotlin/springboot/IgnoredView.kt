// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryHttpMethod
import io.cratis.arc.queries.QueryHttpMethodType

@ReadModel
@AllowAnonymous
data class IgnoredView(val value: String) {
    companion object {
        @Path("/api/ignored")
        @QueryHttpMethod(QueryHttpMethodType.QUERY)
        fun checkIgnored(input: IgnoredInput): IgnoredView = IgnoredView(input.ignoredText ?: "null")
    }
}
