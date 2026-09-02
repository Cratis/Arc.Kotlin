// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryRequest;

/** Deliberately invalid subtype shape used to verify fail-closed KSP classification. */
public final class QueryRequestSubtype extends QueryRequest {
    public QueryRequestSubtype() {
        super(new FullyQualifiedQueryName("io.cratis.arc.contracts.negative.invalid"));
    }
}
