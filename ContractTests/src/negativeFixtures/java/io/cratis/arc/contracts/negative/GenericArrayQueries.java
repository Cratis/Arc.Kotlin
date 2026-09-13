// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import java.util.List;
import java.util.UUID;

@ReadModel
public record GenericArrayQueries(String value) {
    public static GenericArrayQueries wildcard(List<? extends UUID>[] ids) {
        return new GenericArrayQueries(Integer.toString(ids.length));
    }
    public static GenericArrayQueries raw(List[] ids) {
        return new GenericArrayQueries(Integer.toString(ids.length));
    }
    public static <T> GenericArrayQueries parameter(T[] ids) {
        return new GenericArrayQueries(Integer.toString(ids.length));
    }
}
