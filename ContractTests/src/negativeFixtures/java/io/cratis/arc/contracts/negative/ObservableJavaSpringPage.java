// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import java.util.concurrent.Flow;
import org.springframework.data.domain.Page;

/** Invalid Java observable Spring Data page return. */
@ReadModel
public record ObservableJavaSpringPage(String value) {
    public static Flow.Publisher<Page<ObservableJavaSpringPage>> invalid() {
        return null;
    }
}
