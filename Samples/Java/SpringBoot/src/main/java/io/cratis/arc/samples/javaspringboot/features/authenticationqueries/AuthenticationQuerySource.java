// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.authenticationqueries;

import io.cratis.arc.queries.ObservableState;
import io.cratis.arc.samples.javaspringboot.features.SampleTicker;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** Publishes both authentication showcase streams every two seconds. */
@Component
public final class AuthenticationQuerySource {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObservableState<AuthenticationQueryItem> anonymous =
        new ObservableState<>(new AuthenticationQueryItem("Anonymous stream — " + stamp()));
    private final ObservableState<AuthenticationQueryItem> authenticated =
        new ObservableState<>(new AuthenticationQueryItem("Authenticated stream — " + stamp()));

    /**
     * Initializes a new instance of the {@link AuthenticationQuerySource} class.
     *
     * @param ticker The shared sample scheduler.
     */
    public AuthenticationQuerySource(SampleTicker ticker) {
        ticker.everySeconds(2, () -> {
            var now = stamp();
            anonymous.set(new AuthenticationQueryItem("Anonymous stream — " + now));
            authenticated.set(new AuthenticationQueryItem("Authenticated stream — " + now));
        });
    }

    /**
     * Observes the stream that allows anonymous access.
     *
     * @return A publisher of the anonymous stream.
     */
    public Flow.Publisher<AuthenticationQueryItem> observeAnonymous() {
        return anonymous;
    }

    /**
     * Observes the stream that requires authentication.
     *
     * @return A publisher of the authenticated stream.
     */
    public Flow.Publisher<AuthenticationQueryItem> observeAuthenticated() {
        return authenticated;
    }

    private static String stamp() {
        return OffsetDateTime.now().format(STAMP);
    }
}
