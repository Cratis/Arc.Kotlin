// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authentication.AsyncAuthenticationHandler
import io.cratis.arc.authentication.AuthenticationHandler
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.DependencyDescriptor
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.MethodParameter
import org.springframework.core.OrderComparator
import org.springframework.core.Ordered
import org.springframework.core.ResolvableType
import java.util.IdentityHashMap

/** Dependency-resolved originals, ordered before any Java handler is adapted. */
internal object AuthenticationContributions {
    fun ordered(factory: DefaultListableBeanFactory): List<Any> {
        val method = ArcAutoConfiguration::class.java.getMethod(
            "arcAuthentication", ObjectProvider::class.java, ObjectProvider::class.java
        )
        val contributions = linkedMapOf<String, Any>()
        for ((index, type) in listOf(AuthenticationHandler::class.java, AsyncAuthenticationHandler::class.java).withIndex()) {
            // Resolve a map to retain both names and objects, including non-bean resolvable dependencies.
            // Spring copies and nests this descriptor for each element: the original ObjectProvider<T>
            // parameter then resolves to T, preserving its annotations, family and parameter name.
            // Only the root shape is a map; do not pre-nest the original method parameter.
            val descriptor = object : DependencyDescriptor(MethodParameter(method, index), false) {
                override fun getDependencyType(): Class<*> = Map::class.java
                override fun getResolvableType(): ResolvableType =
                    ResolvableType.forClassWithGenerics(Map::class.java, String::class.java, type)
            }
            val candidates = factory.resolveDependency(descriptor, "arcAuthentication") as Map<*, *>? ?: continue
            for ((name, contribution) in candidates) {
                check(name is String && type.isInstance(contribution)) { "Unexpected authentication dependency '$name'." }
                contributions[name] = requireNotNull(contribution)
            }
        }
        val namesByInstance = IdentityHashMap<Any, String>()
        contributions.forEach { (name, instance) -> namesByInstance[instance] = name }
        val comparator = (factory.dependencyComparator as? OrderComparator ?: OrderComparator.INSTANCE)
            .withSourceProvider { instance ->
                namesByInstance[instance]?.let { orderSources(factory, it, instance) }
            }
        return contributions.values.sortedWith(comparator)
    }

    // These are the public metadata sources used by Spring 7's factory-aware ordered stream:
    // explicit definition order, resolved @Bean method, then target type, before the instance fallback.
    private fun orderSources(factory: DefaultListableBeanFactory, name: String, instance: Any): Array<Any>? {
        val definition = try {
            factory.getMergedBeanDefinition(name)
        } catch (_: NoSuchBeanDefinitionException) {
            return null // Resolvable dependency or programmatic singleton without a bean definition.
        }
        val sources = mutableListOf<Any>()
        definition.getAttribute(AbstractBeanDefinition.ORDER_ATTRIBUTE)?.let { order ->
            check(order is Int) { "Invalid order attribute for authentication contribution '$name': ${order.javaClass.name}" }
            sources.add(Ordered { order })
        }
        if (definition is RootBeanDefinition) {
            definition.resolvedFactoryMethod?.let(sources::add)
            definition.targetType?.takeIf { it != instance.javaClass }?.let(sources::add)
        }
        return sources.toTypedArray()
    }
}
