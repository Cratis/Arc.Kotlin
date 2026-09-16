// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.authentication.AsyncAuthenticationHandler;
import io.cratis.arc.authentication.AuthenticationRequestContext;
import io.cratis.arc.authentication.AuthenticationResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

class AuthenticationJavaFixture implements AsyncAuthenticationHandler {
    private final List<String> calls;
    private final AuthenticationResult result;

    AuthenticationJavaFixture(List<String> calls, AuthenticationResult result) {
        this.calls = calls;
        this.result = result;
    }

    @Override
    public CompletionStage<AuthenticationResult> handleAuthentication(AuthenticationRequestContext context) {
        calls.add("async");
        return CompletableFuture.completedFuture(result);
    }

    @Order(-20)
    static final class High extends AuthenticationJavaFixture {
        High(List<String> calls, AuthenticationResult result) { super(calls, result); }
    }

    @Order(20)
    static final class Low extends AuthenticationJavaFixture {
        Low(List<String> calls, AuthenticationResult result) { super(calls, result); }
    }

    static final class WithOrder extends AuthenticationJavaFixture implements Ordered {
        private final int order;
        WithOrder(List<String> calls, AuthenticationResult result, int order) {
            super(calls, result);
            this.order = order;
        }
        @Override
        public int getOrder() { return order; }
    }
}
