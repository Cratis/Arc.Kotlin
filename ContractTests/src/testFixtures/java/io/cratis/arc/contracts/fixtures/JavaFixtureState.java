// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.concepts.ArcEnum;
import io.cratis.arc.concepts.ArcEnumValue;

/** Java Arc enum whose constructor literals are preserved as wire values. */
public enum JavaFixtureState implements ArcEnum {
    @ArcEnumValue(0)
    UNKNOWN(0),
    @ArcEnumValue(31)
    READY(31);

    private final int wireValue;

    JavaFixtureState(int wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public int value() {
        return wireValue;
    }
}
