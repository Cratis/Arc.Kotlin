// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.polymorphism.DerivedTypeRegistry
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.validation.FluentModelValidator
import io.cratis.arc.validation.ModelValidator

/** Registers every generated artifact exposed by an [ArcArtifactModule]. */
public object ArcArtifactModuleRegistry {
    /**
     * Combines ordered application validators with generated contributions. Imperative validators retain
     * their order and multiplicity. Fluent declarations run once per exact class: matching application
     * instances win, then remaining generated declarations run in class-name order. Conflicts and fluent
     * application declarations without compiler contributions fail closed at registration.
     */
    @JvmStatic
    @JvmOverloads
    public fun modelValidators(
        modules: Iterable<ArcArtifactModule>,
        validators: Iterable<ModelValidator<*>> = emptyList()
    ): List<ModelValidator<*>> {
        val generated = linkedMapOf<Class<*>, FluentModelValidator<*>>()
        for (module in modules) {
            for (registration in java.util.List.copyOf(module.fluentValidators)) {
                val candidate = registration.validator
                val previous = generated.putIfAbsent(candidate.javaClass, candidate)
                require(previous == null || previous.modelType == candidate.modelType && previous.rules == candidate.rules) {
                    "Conflicting fluent validator contributions for '${candidate.javaClass.name}'."
                }
            }
        }
        val result = mutableListOf<ModelValidator<*>>()
        val seen = mutableSetOf<Class<*>>()
        for (validator in validators) {
            if (validator is FluentModelValidator<*>) {
                val expected = generated[validator.javaClass]
                require(expected != null) {
                    "Fluent validator '${validator.javaClass.name}' has no compiler metadata contribution; regenerate and register its artifact module."
                }
                require(validator.modelType == expected.modelType && validator.rules == expected.rules) {
                    "Fluent validator '${validator.javaClass.name}' conflicts with its compiler metadata contribution."
                }
                if (!seen.add(validator.javaClass)) continue
            }
            result.add(validator)
        }
        generated.entries.sortedBy { it.key.name }.forEach { (type, validator) ->
            if (seen.add(type)) result.add(validator)
        }
        return java.util.List.copyOf(result)
    }

    /** Registers [module] with the supplied runtime registries. */
    @JvmStatic
    public fun register(
        module: ArcArtifactModule,
        commandHandlers: CommandHandlerRegistry,
        queryPerformers: QueryPerformerRegistry
    ) {
        module.commandHandlers.forEach(commandHandlers::register)
        module.queryPerformers.forEach(queryPerformers::register)
    }

    /**
     * Registers the base-to-derivative mappings [module] declares, so `_derivedTypeId` resolves when reading.
     *
     * Registration is idempotent, so registering the same module or an overlapping module twice is safe. Do this
     * before the first polymorphic value is read.
     */
    @JvmStatic
    public fun registerDerivedTypes(module: ArcArtifactModule, derivedTypes: DerivedTypeRegistry) {
        module.derivedTypes.forEach { registration ->
            derivedTypes.register(registration.baseType, registration.derivedType)
        }
    }
}
