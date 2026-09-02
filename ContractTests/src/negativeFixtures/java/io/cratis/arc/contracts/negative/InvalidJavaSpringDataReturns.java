// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;

/** Invalid Java Spring Data page return shapes. */
@ReadModel
public record InvalidJavaSpringDataReturns(String value) {
    /** Wildcard pages are not exact Page<T> returns. */
    public static Page<? extends InvalidJavaSpringDataReturns> wildcard() {
        return Page.empty();
    }

    /** Nullable pages are not exact non-null Page<T> returns. */
    @Nullable
    public static Page<InvalidJavaSpringDataReturns> nullable() {
        return null;
    }
}
