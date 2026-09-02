// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.results.merge
import io.cratis.arc.results.withValidationResults

/** Immutable output of the generated command `provide` phase. */
public class CommandPreparation(
    providedValues: Collection<Any>,
    /** Blocking control result, or a clean success when preparation may continue. */
    public val controlResult: CommandResult<*>
) {
    /** Ordered values available to generated `handle` argument resolution. */
    public val providedValues: List<Any> = java.util.List.copyOf(providedValues)

    public companion object {
        /** Empty preparation used by handlers whose command has no `provide` method. */
        @JvmStatic
        public fun empty(correlationId: java.util.UUID): CommandPreparation =
            CommandPreparation(emptyList(), CommandResult.success(correlationId))

        /** Flattens and separates a generated `provide` return value without reflection. */
        @JvmStatic
        public fun from(value: Any?, context: CommandContext): CommandPreparation {
            val candidates = mutableListOf<Any>()
            var control: CommandResult<*> = CommandResult.success(context.correlationId)
            flatten(value).forEach { provided ->
                val fragment = controlResult(provided, context.correlationId)
                if (fragment == null) candidates.add(provided) else control = control.merge(fragment)
            }
            val blocking = control.validationResults.filter { validation ->
                val allowed = context.allowedValidationSeverity
                if (allowed == null) validation.severity == ValidationResultSeverity.Error
                else validation.severity.value() > allowed.value()
            }
            control = control.withValidationResults(blocking)
            return if (control.isSuccess) {
                CommandPreparation(candidates, CommandResult.success(context.correlationId))
            } else {
                CommandPreparation(emptyList(), control)
            }
        }

        private fun flatten(value: Any?): List<Any> {
            val unwrapped = unwrap(value) ?: return emptyList()
            return when (unwrapped) {
                Unit -> emptyList()
                is CommandProvidedValues -> unwrapped.values.flatMap(::flatten)
                is Pair<*, *> -> listOfNotNull(unwrap(unwrapped.first), unwrap(unwrapped.second))
                is Triple<*, *, *> -> listOfNotNull(
                    unwrap(unwrapped.first),
                    unwrap(unwrapped.second),
                    unwrap(unwrapped.third)
                )
                else -> listOf(unwrapped)
            }
        }

        private fun unwrap(value: Any?): Any? = when (value) {
            is ArcOneOf<*> -> unwrap(value.value)
            else -> value
        }

        private fun controlResult(value: Any, correlationId: java.util.UUID): CommandResult<*>? = when (value) {
            is CommandResult<*> -> value
            is ValidationResult -> CommandResult.invalid(correlationId, listOf(value))
            is AuthorizationResult -> if (value.isAuthorized) CommandResult.success(correlationId)
            else CommandResult.unauthorized(correlationId, value.failureReason)
            is Iterable<*> -> value.toList().takeIf { it.isNotEmpty() && it.all { item -> item is ValidationResult } }
                ?.let { CommandResult.invalid(correlationId, it.map { item -> item as ValidationResult }) }
            is Array<*> -> value.toList().takeIf { it.isNotEmpty() && it.all { item -> item is ValidationResult } }
                ?.let { CommandResult.invalid(correlationId, it.map { item -> item as ValidationResult }) }
            else -> null
        }
    }
}
