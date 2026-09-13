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
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

internal class ArcAuthenticationResolvableDependencyTests {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))

    @Test
    fun `only resolvable coroutine activates authentication and supplies its principal`() = verifyOnly(false)

    @Test
    fun `only resolvable async activates authentication and supplies its principal`() = verifyOnly(true)

    @Test
    fun `resolvable coroutine is globally ordered between named async factory contributions`() = verifyMixed(false, false)

    @Test
    fun `resolvable async is globally ordered between named coroutine factory contributions`() = verifyMixed(true, false)

    @Test
    fun `resolvable coroutine rejection is terminal among named async contributions`() = verifyMixed(false, true)

    @Test
    fun `resolvable async rejection is terminal among named coroutine contributions`() = verifyMixed(true, true)

    @Test
    fun `coroutine prototypes are constructed once alongside a resolvable async contribution`() = verifyPrototypes(false)

    @Test
    fun `async prototypes are constructed once alongside a resolvable coroutine contribution`() = verifyPrototypes(true)

    private fun verifyOnly(async: Boolean) {
        val state = State()
        val contribution = contribution(async, state, "resolvable", 0, state.result)
        runner.withInitializer { context ->
            context.beanFactory.registerResolvableDependency(handlerType(async), contribution)
        }.run { context ->
            val authentication = context.getBean(Authentication::class.java)
            assertTrue(authentication.hasHandlers)
            assertSame(state.result, runBlocking { authentication.handleAuthentication(AuthenticationRequestContext()) })
            assertEquals("resolvable", state.result.principal?.id)
            assertEquals(listOf("resolvable"), state.calls)
        }
    }

    private fun verifyMixed(async: Boolean, reject: Boolean) {
        val state = State(if (reject) AuthenticationResult.failed(AuthenticationFailureReason.of("resolvable-rejected")) else success("resolvable"))
        runner.withBean(State::class.java, { state })
            .withUserConfiguration(if (async) CoroutinePeers::class.java else AsyncPeers::class.java)
            .withInitializer { context ->
                context.beanFactory.registerResolvableDependency(handlerType(async), contribution(async, state, "resolvable", 0, state.result))
            }.run { context ->
                val result = runBlocking { context.getBean(Authentication::class.java).handleAuthentication(AuthenticationRequestContext()) }
                assertSame(state.result, result)
                assertEquals(listOf("named-first", "resolvable"), state.calls)
            }
    }

    private fun verifyPrototypes(async: Boolean) {
        val state = State(AuthenticationResult.ANONYMOUS)
        val creations = mutableListOf<String>()
        runner.withInitializer { context ->
            val factory = context.beanFactory as DefaultListableBeanFactory
            factory.registerResolvableDependency(handlerType(!async), contribution(!async, state, "resolvable", 0, state.result))
            for ((name, priority) in listOf("prototype-first" to -20, "prototype-last" to 20)) {
                factory.registerBeanDefinition(name, RootBeanDefinition(handlerType(async)).apply {
                    scope = BeanDefinition.SCOPE_PROTOTYPE
                    instanceSupplier = Supplier {
                        creations.add(name)
                        contribution(async, state, name, priority, AuthenticationResult.ANONYMOUS)
                    }
                })
            }
        }.run { context ->
            val authentication = context.getBean(Authentication::class.java)
            assertTrue(authentication.hasHandlers)
            assertEquals(listOf("prototype-first", "prototype-last"), creations)
            repeat(2) {
                assertSame(AuthenticationResult.ANONYMOUS, runBlocking { authentication.handleAuthentication(AuthenticationRequestContext()) })
            }
            assertEquals(List(2) { listOf("prototype-first", "resolvable", "prototype-last") }.flatten(), state.calls)
            assertEquals(listOf("prototype-first", "prototype-last"), creations)
        }
    }

    private fun handlerType(async: Boolean): Class<*> =
        if (async) AsyncAuthenticationHandler::class.java else AuthenticationHandler::class.java

    private fun contribution(async: Boolean, state: State, id: String, priority: Int, result: AuthenticationResult): Any =
        if (async) object : AsyncAuthenticationHandler, Ordered {
            override fun getOrder(): Int = priority
            override fun handleAuthentication(context: AuthenticationRequestContext): CompletableFuture<AuthenticationResult> =
                CompletableFuture.completedFuture(state.call(id, result))
        } else object : AuthenticationHandler, Ordered {
            override fun getOrder(): Int = priority
            override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult = state.call(id, result)
        }

    class State(val result: AuthenticationResult = success("resolvable")) {
        val calls = mutableListOf<String>()
        fun call(id: String, result: AuthenticationResult): AuthenticationResult {
            calls.add(id)
            return result
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CoroutinePeers {
        @Bean @Order(-20)
        fun first(state: State): AuthenticationHandler = AuthenticationHandler { state.call("named-first", AuthenticationResult.ANONYMOUS) }
        @Bean @Order(20)
        fun last(state: State): AuthenticationHandler = AuthenticationHandler { state.call("named-last", success("named-last")) }
    }

    @Configuration(proxyBeanMethods = false)
    class AsyncPeers {
        @Bean @Order(-20)
        fun first(state: State): AsyncAuthenticationHandler = AsyncAuthenticationHandler {
            CompletableFuture.completedFuture(state.call("named-first", AuthenticationResult.ANONYMOUS))
        }
        @Bean @Order(20)
        fun last(state: State): AsyncAuthenticationHandler = AsyncAuthenticationHandler {
            CompletableFuture.completedFuture(state.call("named-last", success("named-last")))
        }
    }

    companion object {
        private fun success(id: String): AuthenticationResult = AuthenticationResult.succeeded(ArcPrincipal(id, true))
    }
}
