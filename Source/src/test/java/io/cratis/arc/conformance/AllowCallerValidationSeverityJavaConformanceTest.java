// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.artifacts.AllowCallerValidationSeverity;
import io.cratis.arc.results.ValidationResultSeverity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class AllowCallerValidationSeverityJavaConformanceTest {
    @Test
    void annotationExposesMaximumToJavaReflection() {
        var annotation = AdvisoryCommand.class.getAnnotation(AllowCallerValidationSeverity.class);
        assertEquals(ValidationResultSeverity.Error, annotation.maximum());
    }

    @AllowCallerValidationSeverity(maximum = ValidationResultSeverity.Error)
    private record AdvisoryCommand(String value) { }
}
