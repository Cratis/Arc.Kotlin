// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package fixtures.valid;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

abstract class ValidDeclarations {
    static {
        if (Boolean.parseBoolean("true")) {
            throw new AssertionError("The checker must not initialize test classes");
        }
    }

    @Test
    final void inheritedConcreteMethod() { }

    @ParameterizedTest
    @ValueSource(strings = {"one", "two"})
    void parameterized(String value) { }

    @RepeatedTest(2)
    void repeated() { }

    @TestTemplate
    void template(String resolvedParameter) { }

    // Descriptor-looking strings must not be mistaken for actual annotations.
    String notATest() { return "Lorg/junit/jupiter/api/Test; org.junit.jupiter.api.Test"; }
}

final class FirstChild extends ValidDeclarations { }
final class SecondChild extends ValidDeclarations { }
