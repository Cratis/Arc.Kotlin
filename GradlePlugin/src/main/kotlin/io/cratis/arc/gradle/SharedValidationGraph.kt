// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.metadata.ValidationRuleDescriptor
import org.gradle.api.GradleException

internal data class SharedValidationMember(val name: String, val runtimeType: String, val rules: List<ValidationRuleDescriptor>)
internal data class SharedValidatorDescriptor(val identity: String, val model: String, val members: List<SharedValidationMember>)

/** Closed graph authority for the existing renderer, derived only from verified shared declarations. */
internal class SharedValidationGraph(artifacts: MergedArcArtifacts) {
    private val types = artifacts.types.associateBy { it.fullyQualifiedName }
    private val interfaces = artifacts.interfaces.associateBy { it.fullyQualifiedName }
    private val properties = types.mapValues { it.value.properties } + artifacts.commands.associate { it.typeName to it.properties }
    val declarations = artifacts.sharedValidators.map { declaration ->
        declaration.copy(members = declaration.members.filterNot { member ->
            properties[declaration.model].orEmpty().any { it.name == member.name && it.ignoreValidation }
        })
    }.groupBy { it.model }.mapValues { (_, values) -> values.sortedBy { it.identity } }
    private val derived = artifacts.types.filter { it.baseTypeName != null }.groupBy { it.baseTypeName!! }
    private val active = declarations.keys.toMutableSet()

    init {
        var changed: Boolean
        do {
            changed = false
            (properties + interfaces.mapValues { it.value.properties }).forEach { (name, members) ->
                if (name !in active && (members.any { !it.ignoreValidation && (reaches(it.shape) || it.derivatives.any(active::contains)) } ||
                        derived[name].orEmpty().any { it.fullyQualifiedName in active })) {
                    active += name; changed = true
                }
            }
        } while (changed)
        if (declarations.isNotEmpty()) {
            declarations.forEach { (model, values) ->
                values.flatMap { it.members }.groupBy { it.name }.forEach { (member, rules) ->
                    val normalized = rules.map { it.runtimeType }.distinct()
                    if (normalized.size != 1) unsupported("$model.$member", "conflicting shared JVM member types")
                    ArcFluentValidationMetadataDiscovery.normalizeMember(member, normalized.single(), rules.flatMap { it.rules })
                }
            }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(name: String, path: String) {
                if (name !in active) return
                if (name in interfaces || types[name]?.baseTypeName != null || types[name]?.derivedTypeId != null || derived[name].orEmpty().isNotEmpty())
                    unsupported(path, "polymorphic or inherited shared models are unsupported")
                if (!visiting.add(name)) unsupported(path, "cyclic shared model graphs are unsupported")
                if (visited.add(name)) properties[name].orEmpty().filterNot { it.ignoreValidation }.forEach { member ->
                    if (member.derivatives.isNotEmpty() && (reaches(member.shape) || member.derivatives.any(active::contains)))
                        unsupported("$path.${member.name}", "polymorphic shared edges are unsupported")
                    leaves(member.shape).filter(active::contains).forEach { visit(it, "$path.${member.name}") }
                }
                visiting.remove(name)
            }
            active.sorted().forEach { visit(it, it) }
            // Dynamic input slots could hide an exact runtime shared model. Fail rather than omit it.
            val checked = mutableSetOf<String>()
            fun inspect(shape: TypeShapeDescriptor, path: String) {
                leaves(shape).forEach { name ->
                    if (name in setOf("kotlin.Any", "java.lang.Object")) unsupported(path, "opaque Any/Object input can hide shared rules")
                    if (checked.add(name)) properties[name].orEmpty().filterNot { it.ignoreValidation }.forEach { inspect(it.shape, "$path.${it.name}") }
                }
            }
            artifacts.commands.forEach { command -> command.properties.filterNot { it.ignoreValidation }.forEach { inspect(it.shape, "${command.typeName}.${it.name}") } }
            artifacts.queries.forEach { query -> query.parameters.filter { it.source == io.cratis.arc.metadata.QueryParameterSource.CLIENT }
                .forEach {
                    if (reaches(it.shape) && (query.queryHttpMethod != io.cratis.arc.queries.QueryHttpMethodType.QUERY ||
                            query.transport != io.cratis.arc.queries.QueryTransportType.REQUEST_RESPONSE))
                        unsupported("${query.fullyQualifiedName}.${it.name}", "shared model arguments require request-response RFC QUERY transport")
                    inspect(it.shape, "${query.fullyQualifiedName}.${it.name}")
                } }
        }
    }

    fun contains(name: String): Boolean = name in active
    fun reaches(shape: TypeShapeDescriptor): Boolean = leaves(shape).any(active::contains)
    fun members(name: String): List<PropertyDescriptor> = properties[name].orEmpty()
    fun leaves(shape: TypeShapeDescriptor): List<String> = when (shape.kind) {
        TypeShapeKind.VALUE -> listOf(requireNotNull(shape.typeName))
        TypeShapeKind.SEQUENCE -> leaves(requireNotNull(shape.elementShape))
        TypeShapeKind.MAP -> leaves(requireNotNull(shape.valueShape))
    }
    fun rules(name: String, member: String): List<ValidationRuleDescriptor> = declarations[name].orEmpty()
        .flatMap { it.members }.filter { it.name == member }.flatMap { it.rules }.distinct()

    private fun unsupported(path: String, detail: String): Nothing =
        throw GradleException("[ARCVALIDATION_GRAPH] '$path': $detail; use a concrete acyclic shared input graph or a server-only ModelValidator.")
}
