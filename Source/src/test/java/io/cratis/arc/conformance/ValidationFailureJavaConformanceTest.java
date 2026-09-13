// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.results.ValidationResultSeverity;
import io.cratis.arc.validation.ValidationFailure;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidationFailureJavaConformanceTest {
    private static final UUID ID = UUID.randomUUID();

    @Test
    void staticAndCompanionFactoriesSnapshotAllFieldsWithoutExceptionDetails() {
        Object state = Map.of("visible", "application-state");
        var feedback = new ValidationResult(ValidationResultSeverity.Warning, "safe", List.of("value"),
            state, "applicationReason", "visible detail");
        var payload = new ArrayList<>(List.of(feedback));
        var failure = new ApplicationFailure(payload);
        CommandResult<Void> result = CommandResult.fromException(ID, failure);
        CommandResult<Void> companion = CommandResult.Companion.fromException(ID, failure);
        payload.clear();
        assertEquals(ID, result.getCorrelationId());
        assertFalse(result.isSuccess());
        assertFalse(result.isValid());
        assertTrue(result.isAuthorized());
        assertFalse(result.getHasExceptions());
        assertEquals(List.of(), result.getExceptionMessages());
        assertEquals("", result.getExceptionStackTrace());
        assertNull(result.getResponse());
        assertSame(feedback, result.getValidationResults().get(0));
        assertSame(state, feedback.getState());
        assertEquals(ValidationResultSeverity.Warning, feedback.getSeverity());
        assertEquals("safe", feedback.getMessage());
        assertEquals(List.of("value"), feedback.getMembers());
        assertEquals("applicationReason", feedback.getReason());
        assertEquals("visible detail", feedback.getReasonDetail());
        assertEquals(result.getValidationResults(), companion.getValidationResults());
        assertThrows(UnsupportedOperationException.class, () -> result.getValidationResults().clear());
        assertLegacy(failure, CommandResult.exception(ID, failure));
    }

    @Test
    void malformedJavaPayloadsFallBackToOriginalExceptionWithoutUncheckedCasts() throws Exception {
        var nullEntry = new ArrayList<ValidationResult>();
        nullEntry.add(null);
        var erasedWrongEntry = new ArrayList<ValidationResult>();
        // Exercise a genuinely malformed erased Java payload, without suppressing raw/unchecked compiler checks.
        List.class.getMethod("add", Object.class).invoke(erasedWrongEntry, "not validation");
        for (List<ValidationResult> payload : List.of(List.<ValidationResult>of(), nullEntry, erasedWrongEntry,
                brokenList(new IllegalStateException("iterator secret")))) {
            var failure = new ApplicationFailure(payload);
            assertLegacy(failure, CommandResult.fromException(ID, failure));
        }
        var nullPayload = new ApplicationFailure(null);
        assertLegacy(nullPayload, CommandResult.fromException(ID, nullPayload));
        var getterFailure = new ApplicationFailure(List.of()) {
            @Override public List<ValidationResult> getValidationResults() { throw new IllegalStateException("getter secret"); }
        };
        assertLegacy(getterFailure, CommandResult.fromException(ID, getterFailure));
        var ordinary = new IllegalArgumentException("ordinary");
        assertLegacy(ordinary, CommandResult.fromException(ID, ordinary));
        var wrapper = new IllegalStateException("wrapper", new ApplicationFailure(List.of(ValidationResult.error("safe"))));
        assertLegacy(wrapper, CommandResult.fromException(ID, wrapper));
    }

    @Test
    void cancellationAlwaysWinsIncludingGetterAndIteratorCancellation() {
        var cancellation = new CancellationException("cancel");
        var getter = new ApplicationFailure(List.of()) {
            @Override public List<ValidationResult> getValidationResults() { throw cancellation; }
        };
        assertSame(cancellation, assertThrows(CancellationException.class, () -> CommandResult.fromException(ID, getter)));
        var iterator = new ApplicationFailure(brokenList(cancellation));
        assertSame(cancellation, assertThrows(CancellationException.class, () -> CommandResult.fromException(ID, iterator)));
        var marker = new CancelledFailure();
        assertSame(marker, assertThrows(CancellationException.class, () -> CommandResult.fromException(ID, marker)));
    }

    private static List<ValidationResult> brokenList(RuntimeException failure) {
        return new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public ValidationResult get(int index) { throw failure; }
        };
    }

    private static void assertLegacy(Throwable failure, CommandResult<?> result) {
        CommandResult<Void> legacy = CommandResult.exception(ID, failure);
        assertEquals(legacy.getExceptionMessages(), result.getExceptionMessages());
        assertEquals(legacy.getExceptionStackTrace(), result.getExceptionStackTrace());
        assertEquals(List.of(), result.getValidationResults());
        assertFalse(result.isSuccess());
        assertNull(result.getResponse());
    }

    private static class ApplicationFailure extends RuntimeException implements ValidationFailure {
        private static final long serialVersionUID = 1L;
        private final List<ValidationResult> payload;
        ApplicationFailure(List<ValidationResult> payload) { super("original secret"); this.payload = payload; }
        @Override public List<ValidationResult> getValidationResults() { return payload; }
    }

    private static final class CancelledFailure extends CancellationException implements ValidationFailure {
        private static final long serialVersionUID = 1L;
        @Override public List<ValidationResult> getValidationResults() { throw new AssertionError("must not read"); }
    }
}
