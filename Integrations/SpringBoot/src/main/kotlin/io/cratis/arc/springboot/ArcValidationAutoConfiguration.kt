// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import jakarta.validation.ConstraintViolation
import jakarta.validation.ElementKind
import jakarta.validation.Path
import jakarta.validation.Validator
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.Continuation
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** Optional Jakarta Bean Validation adaptation for Arc commands and queries. */
@AutoConfiguration(after = [ArcAutoConfiguration::class])
@ConditionalOnClass(name = ["jakarta.validation.Validator"])
public class ArcValidationAutoConfiguration {
    /** Adds Jakarta constraint violations from command graphs to the ordinary Arc validation envelope. */
    @Bean("arcJakartaBeanValidationCommandFilter")
    @ConditionalOnBean(Validator::class)
    @ConditionalOnMissingBean(name = ["arcJakartaBeanValidationCommandFilter"])
    public fun arcJakartaBeanValidationCommandFilter(validator: Validator): CommandFilter =
        JakartaBeanValidationCommandFilter(JakartaModelGraphValidator(validator))

    /** Adds Jakarta constraint violations from typed query argument graphs before a query performer is invoked. */
    @Bean("arcJakartaBeanValidationQueryFilter")
    @ConditionalOnBean(Validator::class)
    @ConditionalOnMissingBean(name = ["arcJakartaBeanValidationQueryFilter"])
    public fun arcJakartaBeanValidationQueryFilter(
        validator: Validator,
        performers: QueryPerformerRegistry
    ): QueryFilter = JakartaBeanValidationQueryFilter(JakartaModelGraphValidator(validator), performers)
}

private class JakartaBeanValidationCommandFilter(private val graphValidator: JakartaModelGraphValidator) : CommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> {
        val violations = graphValidator.validate(context.command)
        return if (violations.isEmpty()) {
            CommandResult.success(context.correlationId)
        } else {
            CommandResult.invalid(context.correlationId, violations)
        }
    }
}

private class JakartaBeanValidationQueryFilter(
    private val graphValidator: JakartaModelGraphValidator,
    private val performers: QueryPerformerRegistry
) : QueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> {
        val performer = performers.find(context.queryName)
            ?: return QueryResult.success<Any?>(context.correlationId)
        val violations = graphValidator.validateQuery(context, performer)
        return if (violations.isEmpty()) {
            QueryResult.success<Any?>(context.correlationId)
        } else {
            QueryResult.invalid<Any?>(context.correlationId, violations)
        }
    }
}

private class JakartaModelGraphValidator(private val validator: Validator) {
    fun validate(instance: Any): List<ValidationResult> = validationResults(
        collectGraphViolations(instance, "", cascadeContainer = true)
    )

    fun validateQuery(context: QueryContext, performer: QueryPerformer): List<ValidationResult> {
        val violations = linkedSetOf<MappedViolation>()
        val executableViolations = executableViolations(context, performer)
        executableViolations?.forEach(violations::add)
        performer.descriptor.parameters.asSequence()
            .filter { parameter -> parameter.source == QueryParameterSource.CLIENT }
            .filterNot { executableViolations != null && it.validateRecursively }
            .forEach { parameter ->
                val value = context.request.arguments[parameter.name] ?: return@forEach
                collectGraphViolations(value, parameter.name, parameter.validateRecursively).forEach(violations::add)
            }
        return validationResults(violations)
    }

    private fun collectGraphViolations(
        instance: Any,
        rootPath: String,
        cascadeContainer: Boolean
    ): Set<MappedViolation> {
        val violations = linkedSetOf<MappedViolation>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        visit(instance, rootPath, cascadeContainer, visited, violations)
        return violations
    }

    private fun visit(
        instance: Any,
        rootPath: String,
        cascadeContainer: Boolean,
        visited: MutableSet<Any>,
        violations: MutableSet<MappedViolation>
    ) {
        if (!instance.javaClass.isPrimitive && !visited.add(instance)) return
        validator.validate(instance).forEach { violation ->
            violations.add(violation.toMapped(rootPath))
        }
        if (!cascadeContainer) return

        when {
            instance.javaClass.isArray -> repeat(ReflectArray.getLength(instance)) { index ->
                ReflectArray.get(instance, index)?.let { value ->
                    visit(value, "$rootPath[$index]", true, visited, violations)
                }
            }
            instance is Iterable<*> -> instance.forEachIndexed { index, value ->
                value?.let { visit(it, "$rootPath[$index]", true, visited, violations) }
            }
            instance is Map<*, *> -> instance.entries.forEachIndexed { index, entry ->
                entry.value?.let { value ->
                    visit(value, "$rootPath[${safeMapKey(entry.key, index)}]", true, visited, violations)
                }
            }
        }
    }

    private fun executableViolations(context: QueryContext, performer: QueryPerformer): Set<MappedViolation>? {
        val executable = findExecutable(context, performer) ?: return null
        return validator.forExecutables().validateParameters(executable.target, executable.method, executable.arguments)
            .filter { violation -> violation.isSuppliedClientParameterViolation(context, performer.descriptor) }
            .mapTo(linkedSetOf()) { violation -> violation.toExecutableMapped(performer.descriptor) }
    }

    private fun findExecutable(context: QueryContext, performer: QueryPerformer): QueryExecutable? {
        val descriptor = performer.descriptor
        val classLoader = performer.javaClass.classLoader ?: Thread.currentThread().contextClassLoader
        val declaringType = runCatching { Class.forName(descriptor.declaringTypeName, false, classLoader) }.getOrNull()
            ?: return null
        val candidateTypes = listOf(declaringType) + declaringType.declaredClasses.toList()
        val method = candidateTypes.asSequence()
            .flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == descriptor.name }
            .filter { method ->
                method.parameterCount == descriptor.parameters.size ||
                    method.parameterCount == descriptor.parameters.size + 1 &&
                    Continuation::class.java.isAssignableFrom(method.parameterTypes.last())
            }
            .sortedBy { Modifier.isStatic(it.modifiers) }
            .firstOrNull() ?: return null
        val target = executableTarget(context, declaringType, method) ?: return null
        val arguments = method.parameterTypes.mapIndexed { index, parameterType ->
            if (index >= descriptor.parameters.size) {
                null
            } else {
                val parameter = descriptor.parameters[index]
                when (parameter.source) {
                    QueryParameterSource.CLIENT -> context.request.arguments[parameter.name]
                    QueryParameterSource.SERVICE -> context.serviceResolver.resolve(parameterType)
                    QueryParameterSource.QUERY_REQUEST -> context.request
                    QueryParameterSource.QUERY_CONTEXT -> context
                    QueryParameterSource.HOST_ADAPTER -> null
                }
            }
        }.toTypedArray()
        return QueryExecutable(target, method, arguments)
    }

    private fun executableTarget(context: QueryContext, declaringType: Class<*>, method: Method): Any? {
        context.serviceResolver.resolve(method.declaringClass)?.let { return it }
        sequenceOf(method.declaringClass, declaringType).forEach { type ->
            listOf("INSTANCE", "Companion").forEach { fieldName ->
                val value = runCatching { type.getField(fieldName).get(null) }.getOrNull()
                if (value != null && method.declaringClass.isInstance(value)) return value
            }
        }
        return null
    }

    private fun ConstraintViolation<*>.toMapped(rootPath: String): MappedViolation {
        val relativePath = normalizedPath(propertyPath)
        val member = when {
            rootPath.isBlank() -> relativePath
            relativePath.isBlank() -> rootPath
            relativePath.startsWith("[") -> rootPath + relativePath
            else -> "$rootPath.$relativePath"
        }
        return MappedViolation(message, member, constraintDescriptor.annotation.annotationClass.java.name)
    }

    private fun ConstraintViolation<*>.isSuppliedClientParameterViolation(
        context: QueryContext,
        descriptor: QueryDescriptor
    ): Boolean {
        val parameterNode = propertyPath.firstOrNull { node -> node.kind == ElementKind.PARAMETER } ?: return false
        val parameterIndex = (parameterNode as? Path.ParameterNode)?.parameterIndex ?: -1
        val parameter = descriptor.parameters.getOrNull(parameterIndex)
            ?: descriptor.parameters.firstOrNull { descriptorParameter -> descriptorParameter.name == parameterNode.name }
            ?: return false
        return parameter.source == QueryParameterSource.CLIENT &&
            (!parameter.hasDefault || context.request.arguments.containsKey(parameter.name))
    }

    private fun ConstraintViolation<*>.toExecutableMapped(descriptor: QueryDescriptor): MappedViolation {
        val member = normalizedPath(propertyPath) { node ->
            if (node.kind == ElementKind.PARAMETER) {
                val index = (node as? Path.ParameterNode)?.parameterIndex ?: -1
                descriptor.parameters.getOrNull(index)?.name ?: node.name
            } else {
                node.name
            }
        }
        return MappedViolation(message, member, constraintDescriptor.annotation.annotationClass.java.name)
    }

    private fun normalizedPath(path: Path, nodeName: (Path.Node) -> String? = Path.Node::getName): String {
        val result = StringBuilder()
        path.forEach { node ->
            if (node.kind == ElementKind.METHOD || node.kind == ElementKind.CONSTRUCTOR ||
                node.kind == ElementKind.RETURN_VALUE || node.kind == ElementKind.CROSS_PARAMETER
            ) return@forEach
            if (node.isInIterable) {
                result.append('[').append(node.key ?: node.index ?: "?").append(']')
            }
            val name = when (node.kind) {
                ElementKind.BEAN, ElementKind.CONTAINER_ELEMENT -> null
                else -> nodeName(node)
            }
            if (!name.isNullOrBlank()) {
                if (result.isNotEmpty() && result.last() != ']') result.append('.')
                else if (result.isNotEmpty() && result.last() == ']') result.append('.')
                result.append(name)
            }
        }
        return result.toString()
    }

    private fun validationResults(violations: Collection<MappedViolation>): List<ValidationResult> = violations
        .sortedWith(compareBy(MappedViolation::member, MappedViolation::message, MappedViolation::constraintType))
        .map { violation ->
            ValidationResult(
                severity = ValidationResultSeverity.Error,
                message = violation.message,
                members = violation.member.takeIf(String::isNotBlank)?.let(::listOf) ?: emptyList(),
                reason = ValidationResultReasons.RULE
            )
        }

    private fun safeMapKey(key: Any?, index: Int): String = when (key) {
        null -> "null"
        is CharSequence, is Number, is Boolean, is Char, is Enum<*> -> key.toString()
        else -> index.toString()
    }

    private data class QueryExecutable(val target: Any, val method: Method, val arguments: Array<Any?>)
    private data class MappedViolation(val message: String, val member: String, val constraintType: String)
}
