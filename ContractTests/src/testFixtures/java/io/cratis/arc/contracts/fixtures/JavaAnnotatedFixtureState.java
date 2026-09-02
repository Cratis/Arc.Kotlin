// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.concepts.ArcEnum;
import io.cratis.arc.concepts.ArcEnumValue;

/** Java Arc enum using annotations when constructor arguments are not integer literals. */
public enum JavaAnnotatedFixtureState implements ArcEnum {
    @ArcEnumValue(41)
    COMPUTED(compute(41));

    private final int wireValue;

    JavaAnnotatedFixtureState(int wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public int value() {
        return wireValue;
    }

    private static int compute(int value) {
        return value;
    }
}
