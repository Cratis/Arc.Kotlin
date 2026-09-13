// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.authentication.AsyncAuthentication;
import io.cratis.arc.authentication.AsyncAuthenticationHandler;
import io.cratis.arc.authentication.AsyncAuthenticationHandlerAdapter;
import io.cratis.arc.authentication.Authentication;
import io.cratis.arc.authentication.AuthenticationHandler;
import io.cratis.arc.authentication.AuthenticationRequestContext;
import io.cratis.arc.authentication.AuthenticationResult;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.java.JavaAsyncScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthenticationJavaConformanceTest {
    @Test
    void javaFactoryMethodOrderingReachesTheSpringAuthenticationFacade() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration.class))
            .withUserConfiguration(Contributions.class)
            .run(context -> {
                AuthenticationResult result = context.getBean(AsyncAuthentication.class)
                    .handleAuthentication(new AuthenticationRequestContext()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals("async", result.getPrincipal().getId());
                Calls calls = context.getBean(Calls.class);
                assertEquals(List.of("async"), calls.values);

                calls.values.clear();
                // The original two-provider descriptor is still callable on the Spring-managed configuration.
                Authentication direct = context.getBean(ArcAutoConfiguration.class).arcAuthentication(
                    context.getBeanProvider(AuthenticationHandler.class),
                    context.getBeanProvider(AsyncAuthenticationHandler.class));
                try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
                    result = scope.authentication(direct).handleAuthentication(new AuthenticationRequestContext())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertEquals("async", result.getPrincipal().getId());
                    assertEquals(List.of("async"), calls.values);
                }
            });
    }

    @Test
    void originalConstructorAndFactoryRetainStandaloneProviderChainCompatibility() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Contributions.class);
             JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            // Without an injected context, preserve the historical supplied-provider concatenation.
            Authentication authentication = new ArcAutoConfiguration().arcAuthentication(
                context.getBeanProvider(AuthenticationHandler.class),
                context.getBeanProvider(AsyncAuthenticationHandler.class));
            assertTrue(authentication.getHasHandlers());
            AuthenticationResult result = scope.authentication(authentication).handleAuthentication(new AuthenticationRequestContext())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("coroutine", result.getPrincipal().getId());
            assertEquals(List.of("coroutine"), context.getBean(Calls.class).values);
        }
    }

    @Test
    void javaResolvableDependencyParticipatesWithoutBecomingANamedBean() {
        List<String> resolvableCalls = new ArrayList<>();
        AsyncAuthenticationHandler handler = new AuthenticationJavaFixture.WithOrder(
            resolvableCalls, AuthenticationResult.succeeded(new ArcPrincipal("resolvable", true)), -100);
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration.class))
            .withUserConfiguration(Contributions.class)
            .withInitializer(context -> context.getBeanFactory().registerResolvableDependency(AsyncAuthenticationHandler.class, handler))
            .run(context -> {
                Authentication authentication = context.getBean(Authentication.class);
                assertTrue(authentication.getHasHandlers());
                AuthenticationResult result = context.getBean(AsyncAuthentication.class)
                    .handleAuthentication(new AuthenticationRequestContext()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals("resolvable", result.getPrincipal().getId());
                assertEquals(List.of("async"), resolvableCalls);
                assertEquals(List.of(), context.getBean(Calls.class).values);
                assertEquals(1, context.getBeanNamesForType(AsyncAuthenticationHandler.class).length);
            });
    }

    @Test
    void excludedJavaFactoryContributionsAreNotCreatedOrOrderedIntoTheChain() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration.class))
            .withUserConfiguration(Contributions.class, ExcludedContributions.class)
            .run(context -> {
                AuthenticationResult result = context.getBean(AsyncAuthentication.class)
                    .handleAuthentication(new AuthenticationRequestContext()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals("async", result.getPrincipal().getId());
                assertEquals(List.of("async"), context.getBean(Calls.class).values);
                assertFalse(context.getSourceApplicationContext().getBeanFactory().containsSingleton("excludedAsync"));
                assertFalse(context.getSourceApplicationContext().getBeanFactory().containsSingleton("excludedCoroutine"));
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class ExcludedContributions {
        @Bean(autowireCandidate = false)
        @Lazy
        @Order(-100)
        AsyncAuthenticationHandler excludedAsync() {
            throw new AssertionError("Excluded async factory must not be called");
        }

        @Bean(autowireCandidate = false)
        @Lazy
        @Order(-100)
        AuthenticationHandler excludedCoroutine() {
            throw new AssertionError("Excluded coroutine factory must not be called");
        }
    }

    static final class Calls {
        final List<String> values = new ArrayList<>();
    }

    @Configuration(proxyBeanMethods = false)
    static class Contributions {
        @Bean
        Calls calls() { return new Calls(); }

        @Bean
        @Order(-30)
        AsyncAuthenticationHandler async(Calls calls) {
            return new AuthenticationJavaFixture(calls.values, AuthenticationResult.succeeded(new ArcPrincipal("async", true)));
        }

        @Bean
        @Order(30)
        AuthenticationHandler coroutine(Calls calls) {
            return new AsyncAuthenticationHandlerAdapter(context -> {
                calls.values.add("coroutine");
                return CompletableFuture.completedFuture(AuthenticationResult.succeeded(new ArcPrincipal("coroutine", true)));
            });
        }
    }
}
