// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.authenticationqueries;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.authorization.Authorize;
import java.util.concurrent.Flow;

/**
 * Two streams on one protected read model — one of them deliberately open.
 *
 * {@code @Authorize} on the class is the default for every query it declares, and
 * {@code @AllowAnonymous} on {@code anonymous} narrows that one method back down. This is the shape
 * a login screen needs: it must show something before anyone has signed in, without opening the
 * rest of the model.
 *
 * @param message The message payload including the last update timestamp.
 */
@ReadModel
@Authorize
public record AuthenticationQueryItem(String message) {
    /**
     * Gets the anonymous query stream used for login and pre-auth screens.
     *
     * @param source The stream state.
     * @return A publisher that allows anonymous access.
     */
    @AllowAnonymous
    public static Flow.Publisher<AuthenticationQueryItem> anonymous(@FromServices AuthenticationQuerySource source) {
        return source.observeAnonymous();
    }

    /**
     * Gets the authenticated query stream.
     *
     * @param source The stream state.
     * @return A publisher that requires authentication.
     */
    public static Flow.Publisher<AuthenticationQueryItem> authenticated(
        @FromServices AuthenticationQuerySource source) {
        return source.observeAuthenticated();
    }
}
