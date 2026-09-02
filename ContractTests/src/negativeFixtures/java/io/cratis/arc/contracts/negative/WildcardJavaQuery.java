// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import java.util.List;

@ReadModel
public record WildcardJavaQuery(String value) {
    public static List<WildcardJavaQuery> byValues(List<?> values) {
        return values.stream().map(item -> new WildcardJavaQuery(item.toString())).toList();
    }
}
