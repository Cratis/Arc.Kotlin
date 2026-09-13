// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authentication.AsyncAuthenticationHandler
import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.AuthenticationRequestContext
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.authorization.ArcPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanDefinitionHolder
import org.springframework.beans.factory.config.DependencyDescriptor
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.Ordered
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

internal class ArcAuthenticationEligibilityTests {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))
        .withBean(InjectedContributions::class.java)

    @Test
    fun `excluded high priority coroutine cannot decide authentication`() = verifyExcluded(false, true, false)

    @Test
    fun `excluded high priority async cannot decide authentication`() = verifyExcluded(true, true, false)

    @Test
    fun `excluded only coroutine leaves authentication inactive`() = verifyExcluded(false, false, false)

    @Test
    fun `excluded only async leaves authentication inactive`() = verifyExcluded(true, false, false)

    @Test
    fun `lazy excluded coroutine is never instantiated even with an eligible instance of the same type`() =
        verifyExcluded(false, true, true)

    @Test
    fun `lazy excluded async is never instantiated even with an eligible instance of the same type`() =
        verifyExcluded(true, true, true)

    @Test
    fun `lazy excluded only coroutine is never instantiated`() = verifyExcluded(false, false, true)

    @Test
    fun `lazy excluded only async is never instantiated`() = verifyExcluded(true, false, true)

    @Test
    fun `contextual candidate resolver sees each handler family before instantiation`() {
        for (async in listOf(false, true)) {
            val calls = mutableListOf<String>()
            runner.withInitializer { context ->
                val factory = context.beanFactory as DefaultListableBeanFactory
                factory.autowireCandidateResolver = object : ContextAnnotationAutowireCandidateResolver() {
                    override fun isAutowireCandidate(holder: BeanDefinitionHolder, descriptor: DependencyDescriptor): Boolean {
                        if (descriptor.dependencyType == handlerType(async)) {
                            assertEquals(if (async) "asyncHandlers" else "handlers", descriptor.dependencyName)
                            if (holder.beanName == "contextuallyExcluded") return false
                        }
                        return super.isAutowireCandidate(holder, descriptor)
                    }
                }
                factory.registerBeanDefinition("contextuallyExcluded", definition(async, calls, "excluded", -100, true, true))
                factory.registerBeanDefinition("eligible", definition(async, calls, "eligible", 100, true, false))
            }.run { context ->
                val injected = context.getBean(InjectedContributions::class.java)
                assertEquals(1, injected.contributions().size)
                val result = runBlocking { context.getBean(Authentication::class.java).handleAuthentication(AuthenticationRequestContext()) }
                assertEquals("eligible", result.principal?.id)
                assertEquals(listOf("eligible"), calls)
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("contextuallyExcluded"))
            }
        }
    }

    @Test
    fun `ancestor contributions and local name shadowing match injected providers`() {
        for (async in listOf(false, true)) {
            val calls = mutableListOf<String>()
            GenericApplicationContext().use { parent ->
                parent.registerBeanDefinition("shadowed", definition(async, calls, "parent-shadowed", -100, true, true))
                parent.registerBeanDefinition("ancestor", definition(async, calls, "ancestor", 10, true, true))
                parent.registerBeanDefinition("parentExcluded", definition(!async, calls, "parent-excluded", -200, false, true))
                parent.refresh()
                runner.withParent(parent).withInitializer { context ->
                    val factory = context.beanFactory as DefaultListableBeanFactory
                    // A local bean of a different type still shadows its parent's name.
                    factory.registerBeanDefinition("shadowed", RootBeanDefinition(String::class.java, Supplier { "shadow" }))
                    factory.registerBeanDefinition("local", definition(!async, calls, "local", 20, true, false))
                }.run { context ->
                    assertEquals(2, context.getBean(InjectedContributions::class.java).contributions().size)
                    val result = runBlocking { context.getBean(Authentication::class.java).handleAuthentication(AuthenticationRequestContext()) }
                    assertEquals("ancestor", result.principal?.id)
                    assertEquals(listOf("ancestor"), calls)
                    assertFalse(parent.beanFactory.containsSingleton("shadowed"))
                    assertFalse(parent.beanFactory.containsSingleton("parentExcluded"))
                }
            }
        }
    }

    @Test
    fun `nondefault candidates remain excluded just as in injected streams`() {
        for (async in listOf(false, true)) {
            val calls = mutableListOf<String>()
            runner.withInitializer { context ->
                val factory = context.beanFactory as DefaultListableBeanFactory
                factory.registerBeanDefinition("nondefault", definition(async, calls, "nondefault", -100, true, true).apply {
                    isDefaultCandidate = false
                })
            }.run { context ->
                assertEquals(emptyList<Any>(), context.getBean(InjectedContributions::class.java).contributions())
                assertFalse(context.getBean(Authentication::class.java).hasHandlers)
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("nondefault"))
            }
        }
    }

    @Test
    fun `excluded local handler shadows eligible parent handler without resurrecting it`() {
        for (async in listOf(false, true)) {
            val calls = mutableListOf<String>()
            GenericApplicationContext().use { parent ->
                parent.registerBeanDefinition("shadowed", definition(async, calls, "parent", -100, true, true))
                parent.refresh()
                runner.withParent(parent).withInitializer { context ->
                    (context.beanFactory as DefaultListableBeanFactory).registerBeanDefinition(
                        "shadowed", definition(async, calls, "local", -100, false, true)
                    )
                }.run { context ->
                    assertEquals(emptyList<Any>(), context.getBean(InjectedContributions::class.java).contributions())
                    assertFalse(context.getBean(Authentication::class.java).hasHandlers)
                    assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("shadowed"))
                    assertFalse(parent.beanFactory.containsSingleton("shadowed"))
                }
            }
        }
    }

    private fun verifyExcluded(async: Boolean, eligible: Boolean, lazy: Boolean) {
        val calls = mutableListOf<String>()
        var excludedCreations = 0
        runner.withInitializer { context ->
            val factory = context.beanFactory as DefaultListableBeanFactory
            factory.registerBeanDefinition("excluded", definition(async, calls, "excluded", -100, false, lazy) { excludedCreations++ })
            if (eligible) factory.registerBeanDefinition("eligible", definition(async, calls, "eligible", 100, true, false))
        }.run { context ->
            // The old injected-provider path is the executable eligibility oracle, not a plain bean lookup.
            assertEquals(if (eligible) 1 else 0, context.getBean(InjectedContributions::class.java).contributions().size)
            if (lazy) {
                assertEquals(0, excludedCreations)
                assertFalse(context.sourceApplicationContext.beanFactory.containsSingleton("excluded"))
            }
            val authentication = context.getBean(Authentication::class.java)
            assertEquals(eligible, authentication.hasHandlers)
            val result = runBlocking { authentication.handleAuthentication(AuthenticationRequestContext()) }
            if (eligible) {
                assertEquals("eligible", result.principal?.id)
                assertEquals(listOf("eligible"), calls)
            } else {
                assertSame(AuthenticationResult.ANONYMOUS, result)
                assertEquals(emptyList<String>(), calls)
            }
        }
    }

    private fun handlerType(async: Boolean): Class<*> =
        if (async) AsyncAuthenticationHandler::class.java else AuthenticationHandler::class.java

    private fun definition(
        async: Boolean, calls: MutableList<String>, id: String, priority: Int,
        eligible: Boolean, lazy: Boolean, created: () -> Unit = {}
    ): RootBeanDefinition = RootBeanDefinition(
        if (async) AsyncContribution::class.java else CoroutineContribution::class.java
    ).apply {
        instanceSupplier = Supplier {
            created()
            if (async) AsyncContribution(calls, id, priority) else CoroutineContribution(calls, id, priority)
        }
        isAutowireCandidate = eligible
        isLazyInit = lazy
    }

    class InjectedContributions {
        @Autowired private lateinit var handlers: ObjectProvider<AuthenticationHandler>
        @Autowired private lateinit var asyncHandlers: ObjectProvider<AsyncAuthenticationHandler>
        fun contributions(): List<Any> = handlers.orderedStream().toList() + asyncHandlers.orderedStream().toList()
    }

    class CoroutineContribution(private val calls: MutableList<String>, private val id: String, private val priority: Int) :
        AuthenticationHandler, Ordered {
        override fun getOrder(): Int = priority
        override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult {
            calls.add(id)
            return AuthenticationResult.succeeded(ArcPrincipal(id, true))
        }
    }

    class AsyncContribution(private val calls: MutableList<String>, private val id: String, private val priority: Int) :
        AsyncAuthenticationHandler, Ordered {
        override fun getOrder(): Int = priority
        override fun handleAuthentication(context: AuthenticationRequestContext): CompletableFuture<AuthenticationResult> {
            calls.add(id)
            return CompletableFuture.completedFuture(AuthenticationResult.succeeded(ArcPrincipal(id, true)))
        }
    }
}
