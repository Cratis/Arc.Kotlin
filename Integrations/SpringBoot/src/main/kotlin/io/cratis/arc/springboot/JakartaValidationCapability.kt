// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.validation.IgnoreValidationValidator
import io.cratis.arc.validation.ValidationMemberPolicy
import jakarta.validation.Validator
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import jakarta.validation.metadata.ContainerDescriptor
import jakarta.validation.metadata.CascadableDescriptor
import jakarta.validation.metadata.ElementDescriptor
import java.beans.Introspector
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.reflect.full.declaredMemberProperties

/** Never assumes an opaque Validator belongs to a separately registered factory. */
internal class JakartaValidationCapability(selected: Validator, private val identity: String = "provided Validator '${selected.javaClass.name}'") {
    val validator: Validator = when (selected) {
        is IgnoreValidationValidator -> selected
        is LocalValidatorFactoryBean -> if (selected.javaClass == LocalValidatorFactoryBean::class.java)
            IgnoreValidationValidator.fromFactory(selected) else selected
        else -> selected
    }

    fun requireArgumentSupport(value: Any) {
        if (validator is IgnoreValidationValidator) return
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        fun visit(instance: Any) {
            if (!visited.add(instance)) return
            requireSupport(instance.javaClass)
            // Executable parameters cannot opt out. Inspect only root container entries, never a
            // member getter: Jakarta may cascade these before Arc's ordinary root-container walk.
            when {
                instance.javaClass.isArray -> repeat(java.lang.reflect.Array.getLength(instance)) { index ->
                    java.lang.reflect.Array.get(instance, index)?.let(::visit)
                }
                instance is Iterable<*> -> instance.forEach { it?.let(::visit) }
                instance is Map<*, *> -> instance.values.forEach { it?.let(::visit) }
            }
        }
        visit(value)
    }

    fun requireSupport(type: Class<*>) {
        if (validator is IgnoreValidationValidator) return
        val ignored = ignoredMember(type, mutableSetOf()) ?: unprovableCascade(type, mutableSetOf()) ?: return
        throw IllegalStateException("Arc Jakarta $identity cannot guarantee pre-access @IgnoreValidation for '$ignored'. " +
            "Supply IgnoreValidationValidator.fromFactory(applicationValidatorFactory), or Boot's ordinary LocalValidatorFactoryBean; " +
            "Arc will not replace an opaque validator, use an unrelated factory, or filter violations after access.")
    }

    // An opaque validator may discover an ignored member on a runtime subtype only after reading
    // its parent getter. Reject unprovable cascades before that read, rather than walking owner values.
    private fun unprovableCascade(type: Class<*>, visited: MutableSet<Class<*>>): String? {
        if (!visited.add(type) || type.isPrimitive || type.name.startsWith("java.") || type.name.startsWith("kotlin.")) return null
        fun prove(child: Type?, path: String): String? {
            if (child is ParameterizedType && child.rawType is Class<*> &&
                (Iterable::class.java.isAssignableFrom(child.rawType as Class<*>) || Map::class.java.isAssignableFrom(child.rawType as Class<*>))) {
                return child.actualTypeArguments.firstNotNullOfOrNull { prove(it, path) }
            }
            if (child is Class<*> && child.isArray) return prove(child.componentType, path)
            val raw = child as? Class<*>
            if (raw == null || raw == Any::class.java || raw.isInterface || !Modifier.isFinal(raw.modifiers))
                return "$path (unprovable runtime cascade; use the factory adapter)"
            return ignoredMember(raw, mutableSetOf()) ?: unprovableCascade(raw, visited)
        }
        fun declaredType(name: String): Type? {
            val hierarchy = generateSequence(type) { it.superclass }
            hierarchy.forEach { owner ->
                owner.declaredFields.firstOrNull { it.name == name }?.let { return it.genericType }
                owner.declaredMethods.firstOrNull { it.parameterCount == 0 && it.name in setOf(name, "get${name.replaceFirstChar(Char::uppercase)}", "is${name.replaceFirstChar(Char::uppercase)}") }
                    ?.let { return it.genericReturnType }
            }
            return null
        }
        fun inspect(element: ElementDescriptor, path: String, declared: Type? = null): String? {
            if (element is CascadableDescriptor && element.isCascaded) {
                prove(declared ?: element.elementClass, path)?.let { return it }
            }
            if (element is ContainerDescriptor) element.constrainedContainerElementTypes.forEach {
                inspect(it, "$path[]")?.let { found -> return found }
            }
            return null
        }
        validator.getConstraintsForClass(type).constrainedProperties.forEach { property ->
            inspect(property, "${type.name}.${property.propertyName}", declaredType(property.propertyName))?.let { return it }
        }
        return null
    }

    private fun ignoredMember(type: Type, visited: MutableSet<Type>): String? {
        if (!visited.add(type)) return null
        when (type) {
            is ParameterizedType -> {
                type.actualTypeArguments.forEach { ignoredMember(it, visited)?.let { found -> return found } }
                return ignoredMember(type.rawType, visited)
            }
            is GenericArrayType -> return ignoredMember(type.genericComponentType, visited)
            is WildcardType -> return type.upperBounds.firstNotNullOfOrNull { ignoredMember(it, visited) }
        }
        val owner = type as? Class<*> ?: return null
        if (owner.isArray) return ignoredMember(owner.componentType, visited)
        if (owner.isPrimitive || owner.name.startsWith("java.") || owner.name.startsWith("kotlin.")) return null
        val fields = owner.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        val methods = owner.declaredMethods.filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
        val names = fields.map { it.name } + methods.mapNotNull { method ->
            val suffix = when {
                method.name.startsWith("get") -> method.name.drop(3)
                method.name.startsWith("is") -> method.name.drop(2)
                owner.isRecord -> method.name
                else -> return@mapNotNull null
            }
            suffix.takeIf { it.isNotEmpty() }?.let(Introspector::decapitalize)
        } + if (owner.isAnnotationPresent(Metadata::class.java)) owner.kotlin.declaredMemberProperties.map { it.name } else emptyList()
        names.distinct().forEach { name ->
            if (ValidationMemberPolicy.isIgnored(owner, name)) return "${owner.name}.$name"
        }
        (fields.map { it.genericType } + methods.map { it.genericReturnType } + owner.genericInterfaces.toList() + listOfNotNull(owner.genericSuperclass))
            .forEach { ignoredMember(it, visited)?.let { found -> return found } }
        return null
    }
}
