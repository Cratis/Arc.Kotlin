// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import io.cratis.arc.authorization.ArcPrincipal
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AuthenticationTest {
    @Test
    fun `first success wins after anonymous and failed handlers`() = runBlocking {
        val principal = ArcPrincipal("alice", true)
        var lastCalled = false
        val authentication = DefaultAuthentication(
            listOf(
                AuthenticationHandler { AuthenticationResult.ANONYMOUS },
                AuthenticationHandler { AuthenticationResult.failed(AuthenticationFailureReason.of("bad bearer token")) },
                AuthenticationHandler { AuthenticationResult.succeeded(principal) },
                AuthenticationHandler { lastCalled = true; AuthenticationResult.ANONYMOUS }
            )
        )

        val result = authentication.handleAuthentication(AuthenticationRequestContext())

        assertSame(principal, result.principal)
        assertFalse(lastCalled)
    }

    @Test
    fun `all handler failures are retained in handler order`() = runBlocking {
        val authentication = DefaultAuthentication(
            listOf(
                AuthenticationHandler { AuthenticationResult.failed(AuthenticationFailureReason.of("first")) },
                AuthenticationHandler { AuthenticationResult.ANONYMOUS },
                AuthenticationHandler { AuthenticationResult.failed(AuthenticationFailureReason.of("second")) }
            )
        )

        val result = authentication.handleAuthentication(AuthenticationRequestContext())

        assertFalse(result.isAuthenticated)
        assertEquals(listOf("first", "second"), result.failure!!.reasons.map(AuthenticationFailureReason::value))
    }

    @Test
    fun `authentication result has an exhaustive Kotlin outcome view`() {
        val principal = ArcPrincipal("alice", true)
        val reason = AuthenticationFailureReason.of("expired")

        val authenticated = AuthenticationResult.succeeded(principal).outcome
        val failed = AuthenticationResult.failed(reason).outcome
        val anonymous = AuthenticationResult.ANONYMOUS.outcome

        assertSame(principal, (authenticated as AuthenticationOutcome.Authenticated).principal)
        assertEquals(reason, (failed as AuthenticationOutcome.Failed).failure.reason)
        assertSame(AuthenticationOutcome.Anonymous, anonymous)
    }

    @Test
    fun `completion stage handler cancellation cancels its future`() = runBlocking {
        val future = CompletableFuture<AuthenticationResult>()
        val handler = AsyncAuthenticationHandler { future }.asAuthenticationHandler()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            handler.handleAuthentication(AuthenticationRequestContext())
        }

        operation.cancel(CancellationException("request cancelled"))
        operation.join()

        assertTrue(future.isCancelled)
    }
}
