// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.java.BlockingPipelineGuard
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/** One lazy host-neutral graph traversal shared by concept-only and suspending model validation. */
internal class ConceptValidation(
    validators: Iterable<ConceptValidator<*>>,
    modelValidators: Iterable<ModelValidator<*>> = emptyList(),
    exclusions: Iterable<ConceptValidationExclusion> = emptyList()
) {
    private val validators = java.util.List.copyOf(validators.toList())
    private val modelValidators = java.util.List.copyOf(modelValidators.toList()).also { snapshot ->
        // Freeze fluent declarations at filter registration, not on a later request.
        snapshot.filterIsInstance<FluentModelValidator<*>>().forEach { it.rules }
    }
    private val exclusions = java.util.List.copyOf(exclusions.toList())

    // Internal legacy concept-only entry. Iterates the same graph strategy, without a coroutine bridge.
    fun validate(value: Any?, member: String = ""): List<ValidationResult> {
        if (validators.isEmpty()) return emptyList()
        val validatedConcepts = IdentityHashMap<Any, Unit>()
        return nodes(value, member).flatMap { node -> conceptResults(node, validatedConcepts) }.toList()
    }

    suspend fun validate(value: Any?, context: ModelValidationContext): List<ValidationResult> {
        if (validators.isEmpty() && modelValidators.isEmpty()) return emptyList()
        val results = mutableListOf<ValidationResult>()
        val validatedConcepts = IdentityHashMap<Any, Unit>()
        for (entry in nodes(value, context.memberPath)) {
            val node = entry.value
            val member = entry.member
            for (validator in modelValidators) {
                if (!entry.firstVisit || validator.modelType != node.javaClass) continue
                try {
                    // Validate and snapshot the entire Java-provided list before publishing any feedback.
                    val feedback: Any? = validateModel(validator, node, context.atMember(member))
                    require(feedback is List<*>) { "Model validator must return a list." }
                    val snapshot = feedback.map { result ->
                        require(result is ValidationResult) { "Model validator feedback must contain validation results." }
                        result.atModelMember(member)
                    }
                    results.addAll(snapshot)
                } catch (exception: Exception) {
                    exception.rethrowCancellationOrFatal()
                    results.add(failure(member))
                }
            }
            results.addAll(conceptResults(entry, validatedConcepts))
        }
        return results
    }

    private fun ModelValidationContext.atMember(memberPath: String): ModelValidationContext =
        commandContext?.let { ModelValidationContext(it, memberPath) } ?: ModelValidationContext(queryContext!!, memberPath)

    private suspend fun <T : Any> validateModel(
        validator: ModelValidator<T>, value: Any, context: ModelValidationContext
    ): List<ValidationResult> = validator.validate(validator.modelType.cast(value), context)

    private fun conceptResults(node: Node, validatedConcepts: IdentityHashMap<Any, Unit>): List<ValidationResult> {
        if (node.value !is ConceptAs<*> || node.excludeConcept || validatedConcepts.put(node.value, Unit) != null) return emptyList()
        return conceptResults(node.value, node.member)
    }

    private fun conceptResults(value: Any, member: String): List<ValidationResult> {
        if (value !is ConceptAs<*>) return emptyList()
        val results = mutableListOf<ValidationResult>()
        validators.filter { validator -> validator.conceptType.isInstance(value) }.forEach { validator ->
            try {
                validateConcept(validator, value).forEach { result -> results.add(result.atMember(member)) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: RuntimeException) {
                exception.rethrowCancellationOrFatal()
                results.add(failure(member))
            }
        }
        return results
    }

    private fun failure(member: String): ValidationResult = ValidationResult(
        ValidationResultSeverity.Error,
        "The value could not be validated.",
        member.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
        reason = ValidationResultReasons.VALIDATOR_FAILED
    )

    private data class Node(val value: Any, val member: String, val firstVisit: Boolean, val excludeConcept: Boolean)

    private fun nodes(value: Any?, member: String): Sequence<Node> = sequence {
        visit(value, member, IdentityHashMap())
    }

    private suspend fun SequenceScope<Node>.visit(
        value: Any?, member: String, visited: IdentityHashMap<Any, Unit>, excludeConcept: Boolean = false
    ) {
        if (value == null) return
        val firstVisit = visited.put(value, Unit) == null
        // Model/traversal identity is independent of whether a concept rule has actually run.
        // A later required alias can therefore validate a concept first reached on an ignored edge.
        if (!firstVisit && value !is ConceptAs<*>) return
        yield(Node(value, member, firstVisit, excludeConcept))
        if (value is ConceptAs<*> || isTerminal(value)) return
        when (value) {
            is Map<*, *> -> value.entries.forEach { (key, element) ->
                visit(element, child(member, key?.toString().orEmpty()), visited)
            }
            is Iterable<*> -> value.forEachIndexed { index, element ->
                visit(element, indexed(member, index), visited)
            }
            else -> {
                if (value.javaClass.isArray) {
                    repeat(ReflectArray.getLength(value)) { index ->
                        visit(ReflectArray.get(value, index), indexed(member, index), visited)
                    }
                    return
                }
                readableProperties(value).forEach { (name, propertyValue) ->
                    val excludeConcept = exclusions.any { it.ownerType == value.javaClass && it.member == name }
                    visit(propertyValue, child(member, name), visited, excludeConcept)
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
                component.name to try {
                    component.accessor.invoke(value)
                } catch (exception: InvocationTargetException) {
                    exception.rethrowCancellationOrFatal()
                    throw exception
                }
            }
        }
        val kotlinProperties = value::class.memberProperties
            .filter { property -> property.visibility == KVisibility.PUBLIC }
            .sortedBy { property -> property.name }
            .mapNotNull { property ->
                runCatching {
                    property.getter.isAccessible = true
                    property.name to property.getter.call(value)
                }.onFailure { it.rethrowCancellationOrFatal() }.getOrNull()
            }
        if (kotlinProperties.isNotEmpty()) return kotlinProperties
        return value.javaClass.fields
            .filter { field -> Modifier.isPublic(field.modifiers) && !Modifier.isStatic(field.modifiers) }
            .sortedBy { field -> field.name }
            .map { field -> field.name to field.get(value) }
    }

    // Only known invocation/stage wrappers are transparent. Ordinary reflective failures retain
    // their record/Kotlin handling above; fatal Errors never become validation feedback.
    private fun Throwable.rethrowCancellationOrFatal() {
        var target = this
        val seen = IdentityHashMap<Throwable, Unit>()
        while (seen.put(target, Unit) == null) {
            target = when (target) {
                is InvocationTargetException -> target.targetException ?: break
                is CompletionException, is ExecutionException -> target.cause ?: break
                else -> break
            }
        }
        BlockingPipelineGuard.rethrowInterruption(target)
        if (target is CancellationException) throw target
        if (target is Error) throw target
    }

    @Suppress("UNCHECKED_CAST")
    private fun validateConcept(validator: ConceptValidator<*>, concept: ConceptAs<*>): List<ValidationResult> =
        (validator as ConceptValidator<ConceptAs<*>>).validate(concept)

    private fun ValidationResult.atModelMember(owner: String): ValidationResult {
        val resolvedMembers = if (members.isEmpty()) {
            owner.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        } else {
            members.map { member ->
                when {
                    owner.isBlank() -> member
                    member.isEmpty() -> owner
                    member.startsWith("[") -> owner + member
                    else -> child(owner, member)
                }
            }
        }
        return ValidationResult(severity, message, resolvedMembers, state, reason, reasonDetail)
    }

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
