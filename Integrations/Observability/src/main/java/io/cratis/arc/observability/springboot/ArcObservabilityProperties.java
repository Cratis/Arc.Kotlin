// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for Arc Micrometer observations and correlation propagation. */
@ConfigurationProperties("cratis.arc.observability")
public final class ArcObservabilityProperties {
    private boolean enabled = true;
    private boolean correlationBaggageEnabled = true;
    private boolean correlationLoggingEnabled = true;

    /** Gets whether Arc pipeline observations are enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Sets whether Arc pipeline observations are enabled. */
    public void setEnabled(boolean value) {
        enabled = value;
    }

    /** Gets whether correlation identifiers are placed in OpenTelemetry baggage when its API is available. */
    public boolean isCorrelationBaggageEnabled() {
        return correlationBaggageEnabled;
    }

    /** Sets whether correlation identifiers are placed in OpenTelemetry baggage when its API is available. */
    public void setCorrelationBaggageEnabled(boolean value) {
        correlationBaggageEnabled = value;
    }

    /** Gets whether correlation identifiers are placed in the SLF4J MDC when its API is available. */
    public boolean isCorrelationLoggingEnabled() {
        return correlationLoggingEnabled;
    }

    /** Sets whether correlation identifiers are placed in the SLF4J MDC when its API is available. */
    public void setCorrelationLoggingEnabled(boolean value) {
        correlationLoggingEnabled = value;
    }
}
