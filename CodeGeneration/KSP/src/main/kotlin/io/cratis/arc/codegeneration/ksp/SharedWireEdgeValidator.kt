// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/** A wire member of a shared validation model and the model type it leads to. */
private data class WireEdge(val member: String, val target: String)

/** Validates that every model reachable from a fluent validator exposes only generated wire members. */
internal class SharedWireEdgeValidator(
    private val resolver: Resolver,
    private val graphLogger: ArcDiagnosticReporter,
    private val collector: MetadataCollector,
    private val fluentDeclarations: List<FluentDeclaration>
) {
    private val declarations = mutableMapOf<String, KSClassDeclaration>()
    private val edges = mutableMapOf<String, List<WireEdge>>()

    fun validate() {
        if (fluentDeclarations.isEmpty()) return
        val roots = (collector.types.map { it.fullyQualifiedName } + fluentDeclarations.map { it.model }).distinct()
        roots.forEach { name -> declarationNamed(name)?.let { visit(it.asStarProjectedType()) } }
        val active = activeModels()
        active.forEach { name ->
            val declaration = declarations[name] ?: return@forEach
            reportErrors(name, declaration, edges[name].orEmpty().filter { it.target in active })
        }
    }

    private fun declarationNamed(name: String): KSClassDeclaration? =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))

    private fun visit(type: KSType) {
        val declaration = type.declaration as? KSClassDeclaration ?: return
        val name = declaration.qualifiedName?.asString() ?: return
        if (name.startsWith("kotlin.") || name.startsWith("java.")) {
            type.arguments.mapNotNull { it.type?.resolve() }.forEach(::visit)
            return
        }
        if (declarations.putIfAbsent(name, declaration) != null) return
        val members = wireMembers(declaration)
        edges[name] = edgesOf(name, members)
        members.forEach { visit(leaf(it.type.resolve())) }
        edges[name].orEmpty().forEach { edge -> declarationNamed(edge.target)?.let { visit(it.asStarProjectedType()) } }
    }

    private fun wireMembers(declaration: KSClassDeclaration): List<KSPropertyDeclaration> =
        declaration.getAllProperties().filter { it.isPublic() && Modifier.JAVA_STATIC !in it.modifiers }
            .filterNot { IgnoreValidationPolicy.isIgnored(listOfNotNull(it, it.getter), it, resolver, graphLogger) }.toList()

    private fun leaf(type: KSType): KSType =
        if (type.declaration.qualifiedName?.asString() in SHARED_WIRE_COLLECTION_TYPES) {
            type.arguments.singleOrNull()?.type?.resolve() ?: type
        } else {
            type
        }

    private fun edgesOf(name: String, members: List<KSPropertyDeclaration>): List<WireEdge> {
        val declared = members.mapNotNull { member ->
            leaf(member.type.resolve()).declaration.qualifiedName?.asString()?.let { WireEdge(member.simpleName.asString(), it) }
        }
        val collected = collector.propertiesFor(name).orEmpty().filterNot { it.ignoreValidation }
            .map { WireEdge(it.name, it.elementTypeName ?: it.typeName) }
        return (declared + collected).distinct()
    }

    private fun activeModels(): Set<String> {
        val active = fluentDeclarations.map { it.model }.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            edges.forEach { (name, members) ->
                if (name !in active && members.any { it.target in active }) {
                    active += name
                    changed = true
                }
            }
        } while (changed)
        return active
    }

    private fun reportErrors(name: String, declaration: KSClassDeclaration, activeEdges: List<WireEdge>) {
        if (isOpenOrAbstract(declaration)) {
            graphLogger.error(
                ArcDiagnostic.FLUENT_RULE,
                "Shared validation graph '$name' is open, abstract or polymorphic; use concrete final shared input models or server-only validators.",
                declaration
            )
        }
        activeEdges.forEach { edge -> reportInvalidWireMember(name, declaration, edge.member) }
        val wire = collector.propertiesFor(name).orEmpty().map { it.name }.toSet()
        activeEdges.filter { it.member !in wire }.forEach { edge ->
            graphLogger.error(
                ArcDiagnostic.FLUENT_RULE,
                "Shared validation edge '$name.${edge.member}' is not a generated wire member; remove the ignored/inherited edge or use a server-only validator.",
                declaration
            )
        }
    }

    private fun isOpenOrAbstract(declaration: KSClassDeclaration): Boolean =
        Modifier.OPEN in declaration.modifiers || Modifier.SEALED in declaration.modifiers || Modifier.ABSTRACT in declaration.modifiers

    private fun reportInvalidWireMember(name: String, declaration: KSClassDeclaration, member: String) {
        try {
            FluentValidationDiscovery.requireWireMember(declaration, member)
        } catch (exception: IllegalArgumentException) {
            graphLogger.error(ArcDiagnostic.FLUENT_RULE, "Shared validation edge '$name.$member': ${exception.message}.", declaration)
        }
    }
}
