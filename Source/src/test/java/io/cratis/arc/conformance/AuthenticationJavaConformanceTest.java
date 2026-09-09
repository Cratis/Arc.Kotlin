// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authentication.AsyncAuthenticationHandler;
import io.cratis.arc.authentication.AsyncAuthenticationHandlerAdapter;
import io.cratis.arc.authentication.AuthenticationFailureReason;
import io.cratis.arc.authentication.AuthenticationRequestContext;
import io.cratis.arc.authentication.AuthenticationResult;
import io.cratis.arc.authentication.DefaultAuthentication;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.java.JavaAsyncScope;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class AuthenticationJavaConformanceTest {
    @Test
    void completionStageAuthenticationHandlerIsNaturalFromJava() {
        AsyncAuthenticationHandler handler = context -> CompletableFuture.completedFuture(
            AuthenticationResult.failed(AuthenticationFailureReason.of(context.header("Authorization"))));
        AuthenticationRequestContext context = new AuthenticationRequestContext(
            java.util.Map.of("authorization", java.util.List.of("invalid")), java.util.Map.of());

        assertEquals("invalid", handler.handleAuthentication(context).toCompletableFuture().join().getFailure().getReason().value());
    }

    @Test
    void recognizedFailureIsTerminalForJavaHandlerChains() {
        AtomicBoolean laterHandlerCalled = new AtomicBoolean();
        DefaultAuthentication authentication = new DefaultAuthentication(List.of(
            new AsyncAuthenticationHandlerAdapter(context -> CompletableFuture.completedFuture(AuthenticationResult.ANONYMOUS)),
            new AsyncAuthenticationHandlerAdapter(context -> CompletableFuture.completedFuture(
                AuthenticationResult.failed(AuthenticationFailureReason.of("bad bearer token")))),
            new AsyncAuthenticationHandlerAdapter(context -> {
                laterHandlerCalled.set(true);
                return CompletableFuture.completedFuture(AuthenticationResult.succeeded(new ArcPrincipal("alice", true)));
            })));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AuthenticationResult result;
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            result = scope.authentication(authentication)
                .handleAuthentication(new AuthenticationRequestContext())
                .toCompletableFuture()
                .join();
        }

        assertFalse(result.isAuthenticated());
        assertNull(result.getPrincipal());
        assertEquals("bad bearer token", result.getFailure().getReason().value());
        assertFalse(laterHandlerCalled.get());
    }

    @Test
    void anonymousIsReturnedOnlyWhenNoJavaHandlerRecognizedTheRequest() {
        DefaultAuthentication authentication = new DefaultAuthentication(List.of(
            new AsyncAuthenticationHandlerAdapter(context -> CompletableFuture.completedFuture(AuthenticationResult.ANONYMOUS)),
            new AsyncAuthenticationHandlerAdapter(context -> CompletableFuture.completedFuture(AuthenticationResult.ANONYMOUS))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AuthenticationResult result;
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            result = scope.authentication(authentication)
                .handleAuthentication(new AuthenticationRequestContext())
                .toCompletableFuture()
                .join();
        }

        assertSame(AuthenticationResult.ANONYMOUS, result);
    }
}
