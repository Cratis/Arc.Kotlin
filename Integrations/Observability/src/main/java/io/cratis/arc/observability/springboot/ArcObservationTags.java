// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot;

/** Stable low-cardinality tags and non-tagged correlation context keys used by Arc observations. */
public final class ArcObservationTags {
    /** Arc operation, such as execute, validate, open, subscription, or emission. */
    public static final String OPERATION = "arc.operation";
    /** Fully qualified command artifact type. */
    public static final String ARTIFACT = "arc.artifact";
    /** Fully qualified query name. */
    public static final String QUERY = "arc.query";
    /** Bounded result category, such as success, invalid, unauthorized, cancelled, or error. */
    public static final String OUTCOME = "arc.outcome";
    /** Observation context, OpenTelemetry baggage, and logging key for the request correlation identifier. */
    public static final String CORRELATION_ID = "arc.correlation_id";

    private ArcObservationTags() {
    }
}
