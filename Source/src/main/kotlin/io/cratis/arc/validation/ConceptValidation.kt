// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/** Applies reusable concept validators to concepts reachable from a command or query argument graph. */
internal class ConceptValidation(validators: Iterable<ConceptValidator<*>>) {
    private val validators = java.util.List.copyOf(validators.toList())

    fun validate(value: Any?, member: String = ""): List<ValidationResult> {
        if (validators.isEmpty() || value == null) return emptyList()
        val results = mutableListOf<ValidationResult>()
        visit(value, member, IdentityHashMap(), results)
        return results
    }

    private fun visit(
        value: Any?,
        member: String,
        visited: IdentityHashMap<Any, Unit>,
        results: MutableList<ValidationResult>
    ) {
        if (value == null || isTerminal(value) || visited.put(value, Unit) != null) return
        if (value is ConceptAs<*>) {
            validators.filter { validator -> validator.conceptType.isInstance(value) }.forEach { validator ->
                try {
                    validateConcept(validator, value).forEach { result -> results.add(result.atMember(member)) }
                } catch (exception: RuntimeException) {
                    results.add(
                        ValidationResult(
                            ValidationResultSeverity.Error,
                            "The value could not be validated.",
                            member.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
                            reason = ValidationResultReasons.VALIDATOR_FAILED
                        )
                    )
                }
            }
            return
        }
        when (value) {
            is Map<*, *> -> value.entries.forEach { (key, element) ->
                visit(element, child(member, key?.toString().orEmpty()), visited, results)
            }
            is Iterable<*> -> value.forEachIndexed { index, element ->
                visit(element, indexed(member, index), visited, results)
            }
            else -> {
                if (value.javaClass.isArray) {
                    repeat(ReflectArray.getLength(value)) { index ->
                        visit(ReflectArray.get(value, index), indexed(member, index), visited, results)
                    }
                    return
                }
                readableProperties(value).forEach { (name, propertyValue) ->
                    visit(propertyValue, child(member, name), visited, results)
                }
            }
        }
    }

    private fun isTerminal(value: Any): Boolean = value !is ConceptAs<*> && value !is Map<*, *> &&
        value !is Iterable<*> && !value.javaClass.isArray &&
        (value.javaClass.isEnum || value.javaClass.packageName.startsWith("java.") ||
            value.javaClass.packageName.startsWith("kotlin."))

    private fun readableProperties(value: Any): List<Pair<String, Any?>> {
        if (value.javaClass.isRecord) {
            return value.javaClass.recordComponents.map { component ->
                component.name to component.accessor.invoke(value)
            }
        }
        val kotlinProperties = value::class.memberProperties
            .filter { property -> property.visibility == KVisibility.PUBLIC }
            .sortedBy { property -> property.name }
            .mapNotNull { property ->
                runCatching {
                    property.getter.isAccessible = true
                    property.name to property.getter.call(value)
                }.getOrNull()
            }
        if (kotlinProperties.isNotEmpty()) return kotlinProperties
        return value.javaClass.fields
            .filter { field -> Modifier.isPublic(field.modifiers) && !Modifier.isStatic(field.modifiers) }
            .sortedBy { field -> field.name }
            .map { field -> field.name to field.get(value) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateConcept(validator: ConceptValidator<*>, concept: ConceptAs<*>): List<ValidationResult> =
        (validator as ConceptValidator<ConceptAs<*>>).validate(concept)

    private fun ValidationResult.atMember(owner: String): ValidationResult {
        if (owner.isBlank()) return this
        val resolvedMembers = if (members.isEmpty()) {
            listOf(owner)
        } else {
            members.map { member ->
                when (member.lowercase()) {
                    "value", "rawvalue" -> owner
                    else -> child(owner, member)
                }
            }
        }
        return ValidationResult(severity, message, resolvedMembers, state, reason, reasonDetail)
    }

    private fun child(owner: String, name: String): String = if (owner.isBlank()) name else "$owner.$name"
    private fun indexed(owner: String, index: Int): String = if (owner.isBlank()) "[$index]" else "$owner[$index]"
}
