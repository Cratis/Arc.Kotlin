// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;

/** Invalid Java query infrastructure subtype parameter. */
@ReadModel
public record SubtypeJavaQueryInfrastructure(String value) {
    public static SubtypeJavaQueryInfrastructure invalid(QueryRequestSubtype request) {
        return new SubtypeJavaQueryInfrastructure(request.getQueryName().getValue());
    }
}
