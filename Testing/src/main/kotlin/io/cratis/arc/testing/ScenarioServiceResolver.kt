// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.commands.ServiceResolver
import java.util.Collections
import java.util.LinkedHashMap

/** Immutable, exact-[Class]-keyed services for in-process Arc scenarios. */
public class ScenarioServiceResolver private constructor(
    services: Map<Class<*>, Any>
) : ServiceResolver {
    private val services: Map<Class<*>, Any> = Collections.unmodifiableMap(LinkedHashMap(services))

    /** Number of registered service types. */
    public val size: Int get() = services.size

    override fun <T : Any> resolve(type: Class<T>): T? = services[type]?.let(type::cast)

    /** Returns whether an exact service registration exists for [type]. */
    public fun contains(type: Class<*>): Boolean = services.containsKey(type)

    /** Returns a new resolver containing [service], rejecting an already registered exact [type]. */
    public fun <T : Any> put(type: Class<T>, service: T): ScenarioServiceResolver {
        require(!services.containsKey(type)) { "A scenario service is already registered for '${type.name}'." }
        return ScenarioServiceResolver(LinkedHashMap(services).also { it[type] = service })
    }

    /** Kotlin convenience for [put]. */
    public inline fun <reified T : Any> put(service: T): ScenarioServiceResolver = put(T::class.java, service)

    internal fun putUntyped(type: Class<*>, service: Any): ScenarioServiceResolver {
        require(type.isInstance(service)) { "Scenario service must be an instance of '${type.name}'." }
        require(!services.containsKey(type)) { "A scenario service is already registered for '${type.name}'." }
        return ScenarioServiceResolver(LinkedHashMap(services).also { it[type] = service })
    }

    /** Creates a mutable, duplicate-protecting builder initialized from this resolver. */
    public fun toBuilder(): Builder = Builder(this)

    /** Builder for an immutable [ScenarioServiceResolver]. */
    public class Builder {
        private val services = LinkedHashMap<Class<*>, Any>()

        /** Creates an empty builder. */
        public constructor()

        /** Creates a builder initialized from [resolver]. */
        public constructor(resolver: ScenarioServiceResolver) {
            services.putAll(resolver.services)
        }

        /** Adds [service] under its exact [type] and returns this builder. */
        public fun <T : Any> put(type: Class<T>, service: T): Builder {
            require(!services.containsKey(type)) { "A scenario service is already registered for '${type.name}'." }
            services[type] = service
            return this
        }

        /** Kotlin convenience for [put]. */
        public inline fun <reified T : Any> put(service: T): Builder = put(T::class.java, service)

        /** Builds an immutable resolver snapshot. */
        public fun build(): ScenarioServiceResolver = ScenarioServiceResolver(services)
    }

    public companion object {
        /** Creates an empty immutable resolver. */
        @JvmStatic
        public fun empty(): ScenarioServiceResolver = ScenarioServiceResolver(emptyMap())

        /** Creates an empty builder. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** Creates a builder initialized from [resolver]. */
        @JvmStatic
        public fun builder(resolver: ScenarioServiceResolver): Builder = Builder(resolver)
    }
}
