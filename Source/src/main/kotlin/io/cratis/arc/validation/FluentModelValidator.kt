// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.results.ValidationResult
import java.lang.reflect.Modifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.jvmErasure

/**
 * Constructor-authored, exact-model rules evaluated by the existing model validation pipeline.
 * Reading [rules], registration, or evaluation permanently freezes the declaration. Build tooling
 * reads the restricted source grammar; it must never instantiate application validators.
 */
public abstract class FluentModelValidator<T : Any> protected constructor(
    final override val modelType: Class<T>
) : ModelValidator<T> {
    private class Member(val type: Class<*>, val read: (Any) -> Any?)
    private class Chain(val name: String, val accessor: Member, val rules: MutableList<ValidationRuleDescriptor> = mutableListOf())
    private val chains = mutableListOf<Chain>()
    private var frozen: List<FluentValidationMember>? = null
    private val accessors = mutableMapOf<String, Member>()
    private val lock = Any()

    /** Immutable canonical rules, typed with the actual public readable member class. */
    public val rules: List<FluentValidationMember>
        get() = synchronized(lock) {
            frozen ?: java.util.List.copyOf(chains.groupBy { it.name }.toSortedMap().map { (name, entries) ->
                check(entries.all { it.rules.isNotEmpty() }) { "Fluent member '$name' has an empty rule chain." }
                FluentValidationMember(name, entries.first().accessor.type, entries.flatMap { it.rules })
            }).also { frozen = it }
        }

    /** Selects one public Kotlin property, Java record component or public field; paths are not selectors. */
    protected fun ruleFor(member: String): FluentRuleBuilder = synchronized(lock) {
        checkMutable()
        require(member.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) { "Fluent member '$member' must be a direct member name." }
        val accessor = accessors.getOrPut(member) { resolve(member) }
        val chain = Chain(member, accessor)
        chains.add(chain)
        FluentRuleBuilder.create(
            { rule -> synchronized(lock) {
                checkMutable()
                // Check immediately, but leave canonical ordering/dedup until withMessage has bound to its rule.
                FluentValidationRules.snapshot(accessor.type, listOf(rule))
                chain.rules.add(rule)
            } },
            { message -> synchronized(lock) {
                checkMutable()
                check(chain.rules.isNotEmpty()) { "withMessage on '$member' requires a preceding fluent rule." }
                val last = chain.rules.last()
                chain.rules[chain.rules.lastIndex] = ValidationRuleDescriptor(last.ruleName, last.arguments, message)
            } }
        )
    }

    /** Synchronous Java-friendly evaluation. Returns relative member paths and error severity only. */
    public fun validate(model: T): List<ValidationResult> = evaluate(model) {}

    final override suspend fun validate(model: T, context: ModelValidationContext): List<ValidationResult> {
        val coroutine = currentCoroutineContext()
        return evaluate(model) { coroutine.ensureActive() }
    }

    private fun evaluate(model: T, checkActive: () -> Unit): List<ValidationResult> {
        require(model.javaClass == modelType) { "Fluent validator '${javaClass.name}' requires exact model '${modelType.name}'." }
        checkActive()
        val snapshot = rules
        val results = mutableListOf<ValidationResult>()
        for (member in snapshot) {
            checkActive()
            val value = accessors.getValue(member.member).read(model)
            for (rule in member.rules) {
                checkActive()
                if (!FluentValidationRules.valid(rule, value)) {
                    results.add(ValidationResult.error(FluentValidationRules.message(rule, member.member), listOf(member.member)))
                }
            }
        }
        return java.util.List.copyOf(results)
    }

    private fun checkMutable() = check(frozen == null) { "Fluent validator '${javaClass.name}' is frozen; author all rules in its constructor." }

    private fun resolve(member: String): Member {
        if (modelType.isRecord) {
            val component = modelType.recordComponents.firstOrNull { it.name == member && Modifier.isPublic(it.accessor.modifiers) }
            if (component != null) return Member(component.type) { component.accessor.invoke(it) }
        } else {
            val property = modelType.kotlin.memberProperties.firstOrNull { it.name == member && it.visibility == KVisibility.PUBLIC }
            val type = property?.javaGetter?.returnType ?: property?.javaField?.type
            if (property != null && type != null) {
                require(!property.returnType.jvmErasure.isValue || type == property.returnType.jvmErasure.java) {
                    "Fluent member '${modelType.name}.$member' has an erased inline-value type; use an ordinary member or a server-only ModelValidator."
                }
                property.isAccessible = true
                return Member(type) { property.getter.call(it) }
            }
            val field = modelType.fields.firstOrNull { it.name == member && !Modifier.isStatic(it.modifiers) }
            if (field != null) return Member(field.type) { field.get(it) }
        }
        throw IllegalArgumentException("Fluent member '${modelType.name}.$member' must name a public readable member.")
    }
}
