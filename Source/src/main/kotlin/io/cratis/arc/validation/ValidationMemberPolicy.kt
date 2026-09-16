// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import java.beans.Introspector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/** Metadata-only logical-member policy shared by Arc traversal, fluent rules and Jakarta adapters. */
public object ValidationMemberPolicy {
    private class Member(
        val fields: MutableList<Field> = mutableListOf(),
        val getters: MutableList<Method> = mutableListOf(),
        var ignored: Boolean = false
    )

    private class Model(
        val members: Map<String, Member>,
        val kotlinFieldGetters: Map<Field, Method>,
        val getterNames: Map<Method, String>
    )

    private val models = object : ClassValue<Model>() {
        override fun computeValue(type: Class<*>): Model = inspect(type)
    }

    /**
     * Returns whether a direct logical member is ignored, without constructing an owner or calling
     * user code. An unknown member is not ignored. Genuine overrides and implemented interface
     * getters inherit the opt-out monotonically; ambiguous hidden fields fail with a named error.
     */
    @JvmStatic
    public fun isIgnored(ownerType: Class<*>, member: String): Boolean {
        val model = models.get(ownerType)
        val metadata = model.members[member] ?: return false
        checkShape(ownerType, member, metadata, model)
        return metadata.ignored
    }

    @JvmSynthetic
    internal fun sameMember(ownerType: Class<*>, first: String, second: String): Boolean {
        val members = models.get(ownerType).members
        val member = members[first] ?: return false
        return member === members[second]
    }

    // Accessor discovery is also metadata-only. Invocation remains the traversal/evaluator's job.
    internal fun javaGetters(ownerType: Class<*>): List<Pair<String, Method>> {
        val model = models.get(ownerType)
        return ownerType.methods.filter { getterName(it) != null && it.declaringClass != Any::class.java }
            .groupBy { model.getterNames[it] ?: getterName(it)!! }
            .toSortedMap().map { (name, methods) ->
                isIgnored(ownerType, name) // Reject ambiguous selection before any accessor is used.
                val selected = methods.firstOrNull { candidate ->
                    methods.all { it.returnType.isAssignableFrom(candidate.returnType) }
                } ?: throw ambiguous(ownerType, name, "incompatible getter return types")
                name to selected
            }
    }

    private fun inspect(type: Class<*>): Model {
        val members = linkedMapOf<String, Member>()
        val kotlinFieldGetters = mutableMapOf<Field, Method>()
        val getterNames = mutableMapOf<Method, String>()
        val hierarchy = linkedSetOf<Class<*>>()
        fun collect(current: Class<*>?) {
            if (current == null || current == Any::class.java || !hierarchy.add(current)) return
            collect(current.superclass)
            current.interfaces.sortedBy { it.name }.forEach(::collect)
        }
        collect(type)
        for (owner in hierarchy) {
            if (owner.isAnnotationPresent(Metadata::class.java)) {
                for (property in owner.kotlin.declaredMemberProperties) {
                    val field = property.javaField?.takeUnless { Modifier.isStatic(it.modifiers) }
                    val getter = property.javaGetter?.takeUnless { Modifier.isStatic(it.modifiers) }
                    if (field == null && getter == null) continue
                    if (field != null && getter != null) kotlinFieldGetters[field] = getter
                    if (getter != null) getterNames[getter] = property.name
                    if ((getter == null || !Modifier.isPrivate(getter.modifiers)) &&
                        (property.annotations.any { it is IgnoreValidation } ||
                            property.getter.annotations.any { it is IgnoreValidation } ||
                            getter?.isAnnotationPresent(IgnoreValidation::class.java) == true)) {
                        members.getOrPut(property.name) { Member() }.ignored = true
                    }
                }
            }
            if (owner.isRecord) {
                owner.recordComponents.forEach { getterNames[it.accessor] = it.name }
            }
        }
        val declaredGetterNames = getterNames.toMap()
        // Bind real implementation/override identities before introducing JavaBeans aliases. A Java
        // isReady() implementing Kotlin val isReady is the same edge, not a second `ready` property.
        for (owner in hierarchy) {
            for (method in owner.declaredMethods) {
                if (Modifier.isStatic(method.modifiers) || Modifier.isPrivate(method.modifiers) || method.isBridge) continue
                if (method.isSynthetic && method !in declaredGetterNames) continue
                val name = declaredGetterNames[method] ?: getterName(method) ?: continue
                val familyNames = declaredGetterNames.filterKeys { sameGetterFamily(type, method, it) }.values.distinct()
                require(familyNames.size <= 1) {
                    "Ambiguous validation member '${type.name}.$name': an accessor family names multiple Kotlin properties; rename the members."
                }
                val canonicalName = familyNames.singleOrNull() ?: name
                getterNames[method] = canonicalName
                val member = members.getOrPut(canonicalName) { Member() }
                member.getters.add(method)
                member.ignored = member.ignored || method.isAnnotationPresent(IgnoreValidation::class.java)
            }
        }
        for (owner in hierarchy) {
            for (field in owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }) {
                // A field can follow its own declared getter, never an unrelated inherited getter
                // merely sharing an alias. Retain all fields so hidden-field checks still apply.
                val kotlinName = kotlinFieldGetters[field]?.let { getterNames[it] }
                val ownGetterNames = if (kotlinName != null) emptyList() else getterNames.filterKeys {
                    it.declaringClass == owner && getterName(it, it in declaredGetterNames) == field.name
                }.values.distinct()
                require(ownGetterNames.size <= 1) { "Ambiguous validation member '${type.name}.${field.name}': multiple field accessors; rename the members." }
                val name = kotlinName ?: ownGetterNames.singleOrNull() ?: field.name
                val member = members.getOrPut(name) { Member() }
                member.fields.add(field)
                member.ignored = member.ignored || field.isAnnotationPresent(IgnoreValidation::class.java)
            }
        }
        // Jakarta uses JavaBeans names even for Kotlin's `isReady` property name.
        for ((getter, name) in getterNames) {
            val beanName = getterName(getter, getter in declaredGetterNames) ?: continue
            if (beanName == name) continue
            val member = members[name] ?: continue
            val previous = members.putIfAbsent(beanName, member)
            require(previous == null || previous === member) {
                "Ambiguous validation member '${type.name}.$beanName': multiple logical members use this getter name; rename the members."
            }
        }
        return Model(members, kotlinFieldGetters, getterNames)
    }

    private fun checkShape(owner: Class<*>, name: String, member: Member, model: Model) {
        for (first in member.fields) {
            for (second in member.fields) {
                if (first == second) continue
                val firstGetter = model.kotlinFieldGetters[first]
                val secondGetter = model.kotlinFieldGetters[second]
                if (firstGetter == null || secondGetter == null ||
                    !(overrides(firstGetter, secondGetter) || overrides(secondGetter, firstGetter))) {
                    throw ambiguous(owner, name, "hidden fields in '${first.declaringClass.name}' and '${second.declaringClass.name}'")
                }
            }
        }
        if (member.getters.map { it.name }.distinct().size > 1) {
            throw ambiguous(owner, name, "multiple getter names")
        }
        for (field in member.fields) {
            if (member.getters.any { it.declaringClass == field.declaringClass }) continue
            if (member.getters.any {
                    !it.declaringClass.isInterface || !it.declaringClass.isAssignableFrom(field.declaringClass)
                }) {
                throw ambiguous(owner, name, "a hidden field and an unrelated getter")
            }
        }
        for (first in member.getters) {
            for (second in member.getters) {
                if (first != second && !first.declaringClass.isInterface && !second.declaringClass.isInterface &&
                    !overrides(first, second) && !overrides(second, first)) {
                    throw ambiguous(owner, name, "getter declarations are not genuine overrides")
                }
            }
        }
        if (member.getters.isNotEmpty() && member.getters.none { candidate ->
                member.getters.all { it.returnType.isAssignableFrom(candidate.returnType) }
            }) {
            throw ambiguous(owner, name, "incompatible getter return types")
        }
    }

    private fun overrides(derived: Method, base: Method): Boolean =
        derived.name == base.name && derived.declaringClass != base.declaringClass &&
            base.declaringClass.isAssignableFrom(derived.declaringClass) &&
            !Modifier.isPrivate(base.modifiers) && !Modifier.isFinal(base.modifiers) &&
            (Modifier.isPublic(base.modifiers) || Modifier.isProtected(base.modifiers) ||
                derived.declaringClass.packageName == base.declaringClass.packageName) &&
            base.returnType.isAssignableFrom(derived.returnType)

    private fun sameGetterFamily(owner: Class<*>, first: Method, second: Method): Boolean {
        if (first == second || overrides(first, second) || overrides(second, first)) return true
        if (first.name != second.name) return false
        // A superclass method can implement an interface first declared on the runtime subclass.
        fun implementsContract(method: Method, contract: Method): Boolean = contract.declaringClass.isInterface &&
            contract.declaringClass.isAssignableFrom(owner) && method.declaringClass.isAssignableFrom(owner) &&
            Modifier.isPublic(method.modifiers) && contract.returnType.isAssignableFrom(method.returnType)
        return implementsContract(first, second) || implementsContract(second, first)
    }

    private fun getterName(method: Method, knownAccessor: Boolean = false): String? {
        if (Modifier.isStatic(method.modifiers) || method.isBridge || (method.isSynthetic && !knownAccessor) || method.parameterCount != 0) return null
        val name = method.name
        val suffix = when {
            name.startsWith("get") && name.length > 3 && method.returnType != Void.TYPE -> name.substring(3)
            name.startsWith("is") && name.length > 2 && method.returnType == Boolean::class.javaPrimitiveType -> name.substring(2)
            else -> return null
        }
        return Introspector.decapitalize(suffix)
    }

    private fun ambiguous(owner: Class<*>, member: String, detail: String): IllegalArgumentException =
        IllegalArgumentException("Ambiguous validation member '${owner.name}.$member': $detail; rename hidden members or use a genuine property override.")
}
