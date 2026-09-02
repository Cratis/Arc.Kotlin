// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authentication.AsyncAuthenticationHandler;
import io.cratis.arc.authentication.AuthenticationFailureReason;
import io.cratis.arc.authentication.AuthenticationRequestContext;
import io.cratis.arc.authentication.AuthenticationResult;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AuthenticationJavaConformanceTest {
    @Test
    void completionStageAuthenticationHandlerIsNaturalFromJava() {
        AsyncAuthenticationHandler handler = context -> CompletableFuture.completedFuture(
            AuthenticationResult.failed(AuthenticationFailureReason.of(context.header("Authorization"))));
        AuthenticationRequestContext context = new AuthenticationRequestContext(
            java.util.Map.of("authorization", java.util.List.of("invalid")), java.util.Map.of());

        assertEquals("invalid", handler.handleAuthentication(context).toCompletableFuture().join().getFailure().getReason().value());
    }
}
