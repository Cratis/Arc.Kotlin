// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.Command;
import java.util.List;

@Command
public record ConcreteJavaPropertyCommand(
    ConcreteJavaPropertyBase direct,
    @Nullable ConcreteJavaPropertyBase nullable,
    List<ConcreteJavaPropertyBase> list,
    ConcreteJavaPropertyIntermediate annotatedIntermediate,
    TransitivePropertyBase transitive,
    ConcreteJavaPropertyView nested
) {
    public void handle() { }
}
