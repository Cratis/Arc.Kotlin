// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies that observability configuration and telemetry names are ordinary Java APIs. */
final class ArcObservabilityJavaApiTest {
    @Test
    void configurationIsAccessibleFromJava() {
        ArcObservabilityProperties properties = new ArcObservabilityProperties();
        assertTrue(properties.isEnabled());
        assertTrue(properties.isCorrelationBaggageEnabled());
        assertTrue(properties.isCorrelationLoggingEnabled());

        properties.setEnabled(false);
        properties.setCorrelationBaggageEnabled(false);
        properties.setCorrelationLoggingEnabled(false);

        assertFalse(properties.isEnabled());
        assertFalse(properties.isCorrelationBaggageEnabled());
        assertFalse(properties.isCorrelationLoggingEnabled());
        assertEquals("arc.command", ArcObservationNames.COMMAND);
        assertEquals("arc.correlation_id", ArcObservationTags.CORRELATION_ID);
    }
}
