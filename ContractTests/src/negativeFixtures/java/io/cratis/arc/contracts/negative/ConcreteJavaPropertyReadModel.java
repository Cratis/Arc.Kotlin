// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;

@ReadModel
public record ConcreteJavaPropertyReadModel(ConcreteJavaPropertyBase value) {
    public static ConcreteJavaPropertyReadModel find() {
        return new ConcreteJavaPropertyReadModel(new ConcreteJavaPropertyBase());
    }
}
