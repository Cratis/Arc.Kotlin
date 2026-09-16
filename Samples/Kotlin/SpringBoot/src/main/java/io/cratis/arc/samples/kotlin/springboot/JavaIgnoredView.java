// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot;

@io.cratis.arc.artifacts.ReadModel
@io.cratis.arc.authorization.AllowAnonymous
public record JavaIgnoredView(String value) {
    @io.cratis.arc.queries.Path("/api/java-ignored")
    @io.cratis.arc.queries.QueryHttpMethod(io.cratis.arc.queries.QueryHttpMethodType.QUERY)
    public static JavaIgnoredView checkJavaIgnored(JavaIgnoredInput input) {
        return new JavaIgnoredView(input.ignoredText());
    }
}
