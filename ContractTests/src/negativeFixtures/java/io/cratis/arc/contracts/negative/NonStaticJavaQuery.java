// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import java.util.List;

@ReadModel
public record NonStaticJavaQuery(String value) {
    public List<NonStaticJavaQuery> all() {
        return List.of(this);
    }
}
