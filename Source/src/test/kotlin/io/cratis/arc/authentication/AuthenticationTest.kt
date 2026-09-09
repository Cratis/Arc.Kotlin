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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class AuthenticationTest {
    @Test
    fun `first success wins after handlers that did not recognize the request`() = runBlocking {
        val principal = ArcPrincipal("alice", true)
        var lastCalled = false
        val authentication = DefaultAuthentication(
            listOf(
                AuthenticationHandler { AuthenticationResult.ANONYMOUS },
                AuthenticationHandler { AuthenticationResult.ANONYMOUS },
                AuthenticationHandler { AuthenticationResult.succeeded(principal) },
                AuthenticationHandler { lastCalled = true; AuthenticationResult.ANONYMOUS }
            )
        )

        val result = authentication.handleAuthentication(AuthenticationRequestContext())

        assertSame(principal, result.principal)
        assertFalse(lastCalled)
    }

    @Test
    fun `a recognized failure is terminal and a later success cannot override it`() = runBlocking {
        val principal = ArcPrincipal("alice", true)
        var laterHandlerCalled = false
        val authentication = DefaultAuthentication(
            listOf(
                AuthenticationHandler { AuthenticationResult.ANONYMOUS },
                AuthenticationHandler { AuthenticationResult.failed(AuthenticationFailureReason.of("bad bearer token")) },
                AuthenticationHandler {
                    laterHandlerCalled = true
                    AuthenticationResult.succeeded(principal)
                }
            )
        )

        val result = authentication.handleAuthentication(AuthenticationRequestContext())

        assertFalse(result.isAuthenticated)
        assertNull(result.principal)
        assertEquals("bad bearer token", result.failure!!.reason.value())
        assertFalse(laterHandlerCalled)
    }

    @Test
    fun `the first recognized failure ends the chain and later handlers are never consulted`() = runBlocking {
        var laterHandlerCalled = false
        val authentication = DefaultAuthentication(
            listOf(
                AuthenticationHandler { AuthenticationResult.failed(AuthenticationFailureReason.of("first")) },
                AuthenticationHandler {
                    laterHandlerCalled = true
                    AuthenticationResult.failed(AuthenticationFailureReason.of("second"))
                }
            )
        )

        val result = authentication.handleAuthentication(AuthenticationRequestContext())

        assertFalse(result.isAuthenticated)
        assertEquals(listOf("first"), result.failure!!.reasons.map(AuthenticationFailureReason::value))
        assertFalse(laterHandlerCalled)
    }

    @Test
    fun `reasons declared by the rejecting handler are retained in order`() = runBlocking {
        val reasons = listOf(
            AuthenticationFailureReason.of("token expired"),
            AuthenticationFailureReason.of("audience mismatch")
        )
        val authentication = DefaultAuthentication(listOf(AuthenticationHandler { AuthenticationResult.failed(reasons) }))

        val result = authentication.handleAuthentication(AuthenticationRequestContext())

        assertEquals(
            listOf("token expired", "audience mismatch"),
            result.failure!!.reasons.map(AuthenticationFailureReason::value)
        )
    }

    @Test
    fun `anonymous is returned only when no handler recognized the request`() = runBlocking {
        val authentication = DefaultAuthentication(
            listOf(
                AuthenticationHandler { AuthenticationResult.ANONYMOUS },
                AuthenticationHandler { AuthenticationResult.ANONYMOUS }
            )
        )

        val result = authentication.handleAuthentication(AuthenticationRequestContext())

        assertSame(AuthenticationResult.ANONYMOUS, result)
        assertSame(AuthenticationOutcome.Anonymous, result.outcome)
    }

    @Test
    fun `an empty handler chain is anonymous`() = runBlocking {
        val authentication = DefaultAuthentication(emptyList())

        assertFalse(authentication.hasHandlers)
        assertSame(AuthenticationResult.ANONYMOUS, authentication.handleAuthentication(AuthenticationRequestContext()))
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
