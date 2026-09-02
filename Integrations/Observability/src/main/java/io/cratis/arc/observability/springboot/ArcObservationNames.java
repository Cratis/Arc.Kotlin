// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot;

/** Stable Micrometer observation names emitted by the Arc observability starter. */
public final class ArcObservationNames {
    /** Command execute and validate observations. */
    public static final String COMMAND = "arc.command";
    /** One-shot query observations. */
    public static final String QUERY = "arc.query";
    /** Observable-query open, subscription, and emission observations. */
    public static final String OBSERVABLE_QUERY = "arc.observable.query";
    /** Authentication observations. */
    public static final String AUTHENTICATION = "arc.authentication";
    /** Identity-details provider observations. */
    public static final String IDENTITY_DETAILS = "arc.identity.details";

    private ArcObservationNames() {
    }
}
