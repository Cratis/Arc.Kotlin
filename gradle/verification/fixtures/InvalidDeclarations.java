// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package fixtures.invalid;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.params.ParameterizedTest;

abstract class InvalidDeclarations {
    @Test
    String nonVoid() { return "silently undiscovered"; }

    @Test
    private void privateMethod() { }

    @Test
    static void staticMethod() { }

    @Test
    abstract void abstractMethod();

    @ParameterizedTest
    int parameterized(String value) { return value.length(); }

    @RepeatedTest(2)
    int repeated() { return 1; }

    @TestTemplate
    int template() { return 1; }
}
