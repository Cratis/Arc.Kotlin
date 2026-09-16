// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authentication.AsyncAuthenticationHandler
import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationFailureReason
import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.AuthenticationRequestContext
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.authorization.ArcPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

internal class ArcAuthenticationOrderingTests {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
        .withUserConfiguration(UnrelatedLazyBean::class.java)
        .withClassLoader(FilteredClassLoader("jakarta.servlet", "org.springframework.security"))

    @Test
    fun `class order ranks Java async before coroutine`() = verifyClasses(true)

    @Test
    fun `class order ranks coroutine before Java async`() = verifyClasses(false)

    @Test
    fun `Ordered ranks Java async before coroutine`() = verifyOrdered(true)

    @Test
    fun `Ordered ranks coroutine before Java async`() = verifyOrdered(false)

    @Test
    fun `factory method order ranks Java async before coroutine`() = verifyFactory(true)

    @Test
    fun `factory method order ranks coroutine before Java async`() = verifyFactory(false)

    @Test
    fun `first rejection is terminal in either family`() {
        for (asyncFirst in listOf(true, false)) {
            val rejected = AuthenticationResult.failed(AuthenticationFailureReason.of("rejected"))
            val state = State(
                asyncResult = if (asyncFirst) rejected else success("async"),
                coroutineResult = if (asyncFirst) success("coroutine") else rejected
            )
            factoryRunner(asyncFirst, state).run { context ->
                val result = runBlocking { context.getBean(Authentication::class.java).handleAuthentication(AuthenticationRequestContext()) }
                assertSame(rejected, result)
                assertEquals(listOf(if (asyncFirst) "async" else "coroutine"), state.calls)
            }
        }
    }

    @Test
    fun `anonymous alone permits fallthrough in either direction and success stops the chain`() {
        for (asyncFirst in listOf(true, false)) {
            val state = State(
                asyncResult = if (asyncFirst) AuthenticationResult.ANONYMOUS else success("async"),
                coroutineResult = if (asyncFirst) success("coroutine") else AuthenticationResult.ANONYMOUS
            )
            factoryRunner(asyncFirst, state).withUserConfiguration(LastHandler::class.java).run { context ->
                val result = runBlocking { context.getBean(Authentication::class.java).handleAuthentication(AuthenticationRequestContext()) }
                assertEquals(if (asyncFirst) "coroutine" else "async", result.principal?.id)
                assertEquals(if (asyncFirst) listOf("async", "coroutine") else listOf("coroutine", "async"), state.calls)
            }
        }
    }

    @Test
    fun `all anonymous contributions remain anonymous`() {
        val state = State(AuthenticationResult.ANONYMOUS, AuthenticationResult.ANONYMOUS)
        factoryRunner(true, state).run { context ->
            val authentication = context.getBean(Authentication::class.java)
            assertTrue(authentication.hasHandlers)
            assertSame(AuthenticationResult.ANONYMOUS, runBlocking { authentication.handleAuthentication(AuthenticationRequestContext()) })
            assertEquals(listOf("async", "coroutine"), state.calls)
        }
    }

    @Test
    fun `empty contributions produce an inactive anonymous chain`() {
        runner.run { context ->
            val authentication = context.getBean(Authentication::class.java)
            assertFalse(authentication.hasHandlers)
            assertSame(AuthenticationResult.ANONYMOUS, runBlocking { authentication.handleAuthentication(AuthenticationRequestContext()) })
        }
    }

    private fun verifyClasses(asyncFirst: Boolean) {
        val state = State()
        verify(
            runner.withBean(AuthenticationHandler::class.java, {
                if (asyncFirst) LowCoroutine(state) else HighCoroutine(state)
            }).withBean(AsyncAuthenticationHandler::class.java, {
                if (asyncFirst) AuthenticationJavaFixture.High(state.calls, state.asyncResult)
                else AuthenticationJavaFixture.Low(state.calls, state.asyncResult)
            }), state, asyncFirst
        )
    }

    private fun verifyOrdered(asyncFirst: Boolean) {
        val state = State()
        verify(
            runner.withBean(AuthenticationHandler::class.java, { OrderedCoroutine(state, if (asyncFirst) 20 else -20) })
                .withBean(AsyncAuthenticationHandler::class.java, {
                    AuthenticationJavaFixture.WithOrder(state.calls, state.asyncResult, if (asyncFirst) -20 else 20)
                }), state, asyncFirst
        )
    }

    private fun verifyFactory(asyncFirst: Boolean) {
        val state = State()
        verify(factoryRunner(asyncFirst, state), state, asyncFirst)
    }

    private fun factoryRunner(asyncFirst: Boolean, state: State): ApplicationContextRunner =
        runner.withBean(State::class.java, { state })
            .withUserConfiguration(if (asyncFirst) AsyncFirst::class.java else CoroutineFirst::class.java)

    private fun verify(configured: ApplicationContextRunner, state: State, asyncFirst: Boolean) {
        configured.run { context ->
            val authentication = context.getBean(Authentication::class.java)
            assertSame(authentication, context.getBean("arcAuthentication"))
            val result = runBlocking { authentication.handleAuthentication(AuthenticationRequestContext()) }
            val expected = if (asyncFirst) "async" else "coroutine"
            assertEquals(expected, result.principal?.id)
            assertEquals(listOf(expected), state.calls)
            assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("unrelated"))
        }
    }

    class State(
        val asyncResult: AuthenticationResult = success("async"),
        val coroutineResult: AuthenticationResult = success("coroutine")
    ) {
        val calls = mutableListOf<String>()
        fun coroutine(): AuthenticationHandler = AuthenticationHandler {
            calls.add("coroutine")
            coroutineResult
        }
    }

    @Configuration(proxyBeanMethods = false)
    class AsyncFirst {
        @Bean @Order(-20)
        fun async(state: State): AsyncAuthenticationHandler = AuthenticationJavaFixture(state.calls, state.asyncResult)
        @Bean @Order(20)
        fun coroutine(state: State): AuthenticationHandler = state.coroutine()
    }

    @Configuration(proxyBeanMethods = false)
    class CoroutineFirst {
        @Bean @Order(20)
        fun async(state: State): AsyncAuthenticationHandler = AuthenticationJavaFixture(state.calls, state.asyncResult)
        @Bean @Order(-20)
        fun coroutine(state: State): AuthenticationHandler = state.coroutine()
    }

    @Configuration(proxyBeanMethods = false)
    class LastHandler {
        @Bean @Order(100)
        fun last(state: State): AuthenticationHandler = AuthenticationHandler {
            state.calls.add("last")
            success("last")
        }
    }

    @Configuration(proxyBeanMethods = false)
    class UnrelatedLazyBean {
        @Bean @Lazy
        fun unrelated(): String = error("Authentication discovery must not instantiate unrelated lazy beans")
    }

    @Order(-20)
    private class HighCoroutine(state: State) : AuthenticationHandler by state.coroutine()
    @Order(20)
    private class LowCoroutine(state: State) : AuthenticationHandler by state.coroutine()
    private class OrderedCoroutine(state: State, private val priority: Int) : AuthenticationHandler by state.coroutine(), Ordered {
        override fun getOrder(): Int = priority
    }

    companion object {
        private fun success(id: String): AuthenticationResult = AuthenticationResult.succeeded(ArcPrincipal(id, true))
    }
}
