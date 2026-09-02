// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.queries.QueryRequest;

/** Invalid Java duplicate query request infrastructure source. */
@ReadModel
public record DuplicateJavaQueryInfrastructure(String value) {
    public static DuplicateJavaQueryInfrastructure invalid(QueryRequest first, QueryRequest second) {
        return new DuplicateJavaQueryInfrastructure(first.getQueryName().getValue() + second.getQueryName().getValue());
    }
}
