// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.results.ValidationResultReasons;
import io.cratis.arc.results.ValidationResultSeverity;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime conformance for the Java-visible ValidationResult severity factories. */
final class ValidationResultFactoriesJavaConformanceTest {
    @Test
    void severityFactoriesAreCallableAsStaticsWithTheShortForms() {
        ValidationResult information = ValidationResult.information("Nothing to do.");
        ValidationResult warning = ValidationResult.warning("Close to the limit.", List.of("titles"));
        ValidationResult error = ValidationResult.error("A task title is required.", List.of("title"));

        assertEquals(ValidationResultSeverity.Information, information.getSeverity());
        assertTrue(information.getMembers().isEmpty());
        assertEquals(ValidationResultSeverity.Warning, warning.getSeverity());
        assertEquals(List.of("titles"), warning.getMembers());
        assertEquals(ValidationResultSeverity.Error, error.getSeverity());
        assertEquals("A task title is required.", error.getMessage());
        assertEquals(List.of("title"), error.getMembers());
        assertEquals(ValidationResultReasons.RULE, error.getReason());
        assertNull(error.getReasonDetail());
        assertNull(error.getState());
    }

    @Test
    void severityFactoriesCarryStateReasonAndReasonDetail() {
        Object state = new Object();

        ValidationResult result = ValidationResult.error(
            "The read model could not be resolved.",
            List.of("title"),
            state,
            ValidationResultReasons.DEPENDENCY_UNAVAILABLE,
            "commandKey");

        assertSame(state, result.getState());
        assertEquals(ValidationResultReasons.DEPENDENCY_UNAVAILABLE, result.getReason());
        assertEquals("commandKey", result.getReasonDetail());
    }
}
