// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.queries.QueryRequest;
import org.jetbrains.annotations.Nullable;

/** Invalid Java nullable query infrastructure parameter. */
@ReadModel
public record NullableJavaQueryInfrastructure(String value) {
    public static NullableJavaQueryInfrastructure invalid(@Nullable QueryRequest request) {
        return new NullableJavaQueryInfrastructure(request == null ? "none" : request.getQueryName().getValue());
    }
}
