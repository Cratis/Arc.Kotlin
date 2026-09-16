// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

@io.cratis.arc.artifacts.ReadModel
@io.cratis.arc.authorization.AllowAnonymous
public record JavaIgnoreContractView(String value) {
    @io.cratis.arc.queries.Path("/contracts/java-ignore")
    @io.cratis.arc.queries.QueryHttpMethod(io.cratis.arc.queries.QueryHttpMethodType.QUERY)
    public static JavaIgnoreContractView checkJavaIgnoredContract(JavaIgnoreContractInput input) {
        return new JavaIgnoreContractView(input.ignored());
    }
}
