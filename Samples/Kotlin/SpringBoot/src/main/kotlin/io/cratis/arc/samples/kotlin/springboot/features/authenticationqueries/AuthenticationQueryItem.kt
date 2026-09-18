// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.authenticationqueries

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.authorization.Authorize
import io.cratis.arc.samples.kotlin.springboot.features.SampleTicker
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Component

/** Publishes both authentication showcase streams every two seconds. */
@Component
public class AuthenticationQuerySource(ticker: SampleTicker) {
    private val anonymous: MutableStateFlow<AuthenticationQueryItem> =
        MutableStateFlow(AuthenticationQueryItem("Anonymous stream — ${stamp()}"))
    private val authenticated: MutableStateFlow<AuthenticationQueryItem> =
        MutableStateFlow(AuthenticationQueryItem("Authenticated stream — ${stamp()}"))

    init {
        ticker.everySeconds(2) {
            val now = stamp()
            anonymous.value = AuthenticationQueryItem("Anonymous stream — $now")
            authenticated.value = AuthenticationQueryItem("Authenticated stream — $now")
        }
    }

    /** Observes the stream that allows anonymous access. */
    public fun observeAnonymous(): Flow<AuthenticationQueryItem> = anonymous

    /** Observes the stream that requires authentication. */
    public fun observeAuthenticated(): Flow<AuthenticationQueryItem> = authenticated

    private companion object {
        fun stamp(): String = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
}

/**
 * Two streams on one protected read model — one of them deliberately open.
 *
 * `@Authorize` on the class is the default for every query it declares, and `@AllowAnonymous` on
 * `anonymous` narrows that one method back down. This is the shape a login screen needs: it must
 * show something before anyone has signed in, without opening the rest of the model.
 *
 * @property message The message payload including the last update timestamp.
 */
@ReadModel
@Authorize
public data class AuthenticationQueryItem(public val message: String) {
    public companion object {
        /** Gets the anonymous query stream used for login and pre-auth screens. */
        @JvmStatic
        @AllowAnonymous
        public fun anonymous(@FromServices source: AuthenticationQuerySource): Flow<AuthenticationQueryItem> =
            source.observeAnonymous()

        /** Gets the authenticated query stream. */
        @JvmStatic
        public fun authenticated(@FromServices source: AuthenticationQuerySource): Flow<AuthenticationQueryItem> =
            source.observeAuthenticated()
    }
}
