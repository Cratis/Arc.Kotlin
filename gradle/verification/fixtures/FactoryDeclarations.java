// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package fixtures.factory;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

final class FactoryDeclarations {
    // Force prefilter inclusion, proving the actual method annotation is inspected.
    private final String marker = "Lorg/junit/jupiter/api/Test;";

    @TestFactory
    Stream<DynamicTest> factory() {
        return Stream.of(DynamicTest.dynamicTest(marker, () -> { }));
    }
}
