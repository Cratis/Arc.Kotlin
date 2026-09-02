// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.concepts.ArcEnum;
import io.cratis.arc.concepts.ArcEnumValue;
import io.cratis.arc.concepts.Flags;

/** Java flags enum used to verify JVM flags metadata. */
@Flags
public enum JavaFixturePermissions implements ArcEnum {
    @ArcEnumValue(0)
    NONE(0),
    @ArcEnumValue(1)
    READ(1),
    @ArcEnumValue(2)
    WRITE(2);

    private final int wireValue;

    JavaFixturePermissions(int wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    public int value() {
        return wireValue;
    }
}
