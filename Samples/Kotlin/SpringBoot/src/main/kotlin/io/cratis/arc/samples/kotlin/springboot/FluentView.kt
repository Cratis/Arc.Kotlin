// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryHttpMethod
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryRequest

@ReadModel
@AllowAnonymous
data class FluentView(val value: String) {
    companion object {
        @Path("/api/fluent")
        @QueryHttpMethod(QueryHttpMethodType.QUERY)
        fun checkFluent(input: JavaFluentInput? = JavaFluentInput("default")): FluentView = FluentView(input?.name ?: "null")

        @Path("/api/fluent-batch")
        @QueryHttpMethod(QueryHttpMethodType.QUERY)
        fun checkFluentBatch(inputs: Array<JavaFluentInput>? = null, request: QueryRequest): FluentView =
            FluentView(inputs?.size?.toString() ?: if (request.arguments.containsKey("inputs")) "null" else "omitted")
    }
}
