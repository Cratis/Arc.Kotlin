// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor

/** The artifacts a proxy generation run emits, the TypeScript targets they map to and their shared validation graph. */
internal class TypeScriptGenerationModel(
    val artifacts: MergedArcArtifacts,
    val options: ProxyGenerationOptions,
    private val useProxyFileSuffix: Boolean
) {
    val shared = SharedValidationGraph(artifacts)
    val commandTypeNames = artifacts.commands.map(CommandDescriptor::typeName).toSet()

    /**
     * A type answered by configuration is never also generated.
     *
     * Emitting it as well would put a local declaration and an external import of the same name in scope, so a
     * configured mapping has to remove the type from output the way Arc .NET excludes types from a mapped assembly.
     */
    fun isExternallyMapped(typeName: String): Boolean =
        typeName in options.typeMappings ||
            ProxyTypeMappings.npmPackageFor(typeName, options.packageMappings) != null

    val emittedTypes = artifacts.types
        .filterNot { it.fullyQualifiedName in commandTypeNames }
        .filterNot { isExternallyMapped(it.fullyQualifiedName) }
    val emittedInterfaces = artifacts.interfaces.filterNot { isExternallyMapped(it.fullyQualifiedName) }
    val emittedEnums = artifacts.enums.filterNot { isExternallyMapped(it.fullyQualifiedName) }
    private val queryTargets = buildQueryTargets()
    val conceptsBySource = artifacts.concepts.associateBy { concept -> concept.fullyQualifiedName }
    val targets = buildTargets()
    val targetBySource = targets.associateBy(ArtifactTarget::sourceName)

    fun queryTarget(query: QueryDescriptor): ArtifactTarget = queryTargets.getValue(query.fullyQualifiedName)

    private fun buildTargets(): List<ArtifactTarget> {
        val result = mutableListOf<ArtifactTarget>()
        artifacts.commands.forEach {
            result += ArtifactTarget(
                it.typeName, it.name, outputDirectory(it.location), ArtifactKind.COMMAND, useProxyFileSuffix
            )
        }
        emittedTypes.forEach {
            result += ArtifactTarget(
                it.fullyQualifiedName, it.name, outputDirectory(it.location), ArtifactKind.TYPE, useProxyFileSuffix
            )
        }
        emittedInterfaces.forEach {
            result += ArtifactTarget(
                it.fullyQualifiedName, it.name, outputDirectory(it.location), ArtifactKind.INTERFACE, useProxyFileSuffix
            )
        }
        emittedEnums.forEach {
            result += ArtifactTarget(
                it.fullyQualifiedName, it.name, outputDirectory(it.location), ArtifactKind.ENUM, useProxyFileSuffix
            )
        }
        artifacts.queries.forEach { result += queryTarget(it) }
        return result
    }

    private fun buildQueryTargets(): Map<String, ArtifactTarget> = artifacts.queries.associate { query ->
        query.fullyQualifiedName to ArtifactTarget(
            query.fullyQualifiedName,
            upperCamel(query.name),
            outputDirectory(query.location),
            ArtifactKind.QUERY,
            useProxyFileSuffix
        )
    }

    private fun outputDirectory(location: List<String>): List<String> = location.drop(options.segmentsToSkip)
}
