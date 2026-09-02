// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import java.io.File
import java.util.jar.JarFile
import org.gradle.api.GradleException

internal data class DiscoveredArcManifest(val source: String, val manifest: ArcArtifactManifest)

internal data class MergedArcArtifacts(
    val commands: List<CommandDescriptor>,
    val queries: List<QueryDescriptor>,
    val types: List<TypeDescriptor>,
    val enums: List<EnumDescriptor>,
    val interfaces: List<InterfaceDescriptor> = emptyList(),
    val concepts: List<ConceptDescriptor> = emptyList()
)

internal object ArcManifestDiscovery {
    private const val MANIFEST_PREFIX = "META-INF/cratis/arc/"
    private val MAP_STRING_TYPE_NAMES = setOf("kotlin.String", "java.lang.String", "String")
    private val MAP_SAFE_PRIMITIVE_TYPE_NAMES = setOf(
        "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Int", "kotlin.Short", "kotlin.String",
        "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Integer", "java.lang.Short",
        "java.lang.String", "boolean", "byte", "char", "int", "short", "String", "Boolean"
    )

    fun discover(classpath: Iterable<File>): List<DiscoveredArcManifest> {
        val mapper = ArcObjectMapper.create()
        val discovered = mutableListOf<DiscoveredArcManifest>()
        classpath.map(File::getAbsoluteFile).distinctBy { it.normalize().path }.sortedBy(File::getPath).forEach { entry ->
            when {
                entry.isDirectory -> discoverDirectory(entry, mapper).forEach(discovered::add)
                entry.isFile && entry.extension.equals("jar", ignoreCase = true) -> discoverJar(entry, mapper).forEach(discovered::add)
            }
        }
        validate(discovered)
        return discovered.sortedWith(compareBy({ it.manifest.moduleName }, { it.source }))
    }

    fun merge(discovered: Iterable<DiscoveredArcManifest>): MergedArcArtifacts {
        val manifests = discovered.map(DiscoveredArcManifest::manifest)
        return MergedArcArtifacts(
            manifests.flatMap(ArcArtifactManifest::commands).sortedBy(CommandDescriptor::typeName),
            manifests.flatMap(ArcArtifactManifest::queries).sortedBy(QueryDescriptor::fullyQualifiedName),
            manifests.flatMap(ArcArtifactManifest::types).sortedBy(TypeDescriptor::fullyQualifiedName),
            manifests.flatMap(ArcArtifactManifest::enums).sortedBy(EnumDescriptor::fullyQualifiedName),
            manifests.flatMap(ArcArtifactManifest::interfaces)
                .sortedBy(InterfaceDescriptor::fullyQualifiedName)
                .distinctBy(InterfaceDescriptor::fullyQualifiedName),
            manifests.flatMap(ArcArtifactManifest::concepts)
                .sortedBy(ConceptDescriptor::fullyQualifiedName)
                .distinctBy(ConceptDescriptor::fullyQualifiedName)
        )
    }

    private fun discoverDirectory(root: File, mapper: ObjectMapper): List<DiscoveredArcManifest> =
        root.walkTopDown()
            .filter(File::isFile)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                relative.startsWith(MANIFEST_PREFIX) && relative.endsWith(".json")
            }
            .sortedBy(File::getPath)
            .map { file -> readManifest(file.path, mapper) { mapper.readTree(file) } }
            .toList()

    private fun discoverJar(file: File, mapper: ObjectMapper): List<DiscoveredArcManifest> =
        JarFile(file).use { jar ->
            jar.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { it.name.startsWith(MANIFEST_PREFIX) && it.name.endsWith(".json") }
                .sortedBy { it.name }
                .map { entry ->
                    val source = "${file.path}!/${entry.name}"
                    readManifest(source, mapper) {
                        jar.getInputStream(entry).use(mapper::readTree)
                    }
                }
                .toList()
        }

    private fun readManifest(source: String, mapper: ObjectMapper, readTree: () -> JsonNode): DiscoveredArcManifest =
        try {
            val root = readTree() as? ObjectNode
                ?: throw GradleException("Arc artifact manifest in $source must be a JSON object.")
            validateCanonicalManifest(root, source)
            DiscoveredArcManifest(source, mapper.treeToValue(root, ArcArtifactManifest::class.java))
        } catch (exception: GradleException) {
            throw exception
        } catch (exception: Exception) {
            throw GradleException(
                "Invalid Arc artifact manifest in $source: ${exception.message ?: exception.javaClass.simpleName}",
                exception
            )
        }

    private fun validateCanonicalManifest(root: ObjectNode, source: String) {
        val version = root.get("formatVersion")
        if (version == null || !version.isIntegralNumber || !version.canConvertToInt()) {
            throw GradleException(
                "Arc artifact manifest in $source must declare an explicit numeric formatVersion=" +
                    "${ArcArtifactManifest.CURRENT_FORMAT_VERSION}."
            )
        }
        if (version.intValue() != ArcArtifactManifest.CURRENT_FORMAT_VERSION) {
            throw GradleException(
                "Unsupported Arc artifact manifest format ${version.intValue()} in $source; " +
                    "expected ${ArcArtifactManifest.CURRENT_FORMAT_VERSION}."
            )
        }

        root.path("commands").forEachIndexed { commandIndex, command ->
            rejectLegacyFields(
                command,
                setOf("responseTypeName", "responseIsEnumerable"),
                "commands[$commandIndex]",
                "responseValues[].shape",
                source
            )
            validateShapeNodes(
                command.path("properties"),
                "commands[$commandIndex].properties",
                source,
                allowMaps = true
            )
            validateShapeNodes(
                command.path("responseValues"),
                "commands[$commandIndex].responseValues",
                source,
                setOf("typeName", "isEnumerable"),
                allowMaps = false
            )
        }
        root.path("queries").forEachIndexed { queryIndex, query ->
            validateCanonicalNode(
                query,
                "returnShape",
                setOf("returnTypeName", "isEnumerable"),
                "queries[$queryIndex]",
                source
            )
            validateSupportedShape(query.path("returnShape"), "queries[$queryIndex].returnShape", source, false)
            validateShapeNodes(query.path("parameters"), "queries[$queryIndex].parameters", source, allowMaps = false)
            query.path("parameters").forEachIndexed { parameterIndex, parameter ->
                val parameterPath = "queries[$queryIndex].parameters[$parameterIndex]"
                validateCanonicalNode(
                    parameter,
                    "source",
                    setOf("isFromServices"),
                    parameterPath,
                    source
                )
                val hasDefault = parameter.get("hasDefault")
                if (hasDefault == null || !hasDefault.isBoolean) {
                    throw GradleException(
                        "Arc artifact manifest in $source must declare canonical boolean hasDefault at $parameterPath."
                    )
                }
            }
        }
        root.path("types").forEachIndexed { typeIndex, type ->
            validateShapeNodes(type.path("properties"), "types[$typeIndex].properties", source, allowMaps = true)
        }
        root.path("interfaces").forEachIndexed { interfaceIndex, interfaceNode ->
            validateShapeNodes(
                interfaceNode.path("properties"),
                "interfaces[$interfaceIndex].properties",
                source,
                allowMaps = true
            )
        }
    }

    private fun validateShapeNodes(
        nodes: JsonNode,
        path: String,
        source: String,
        legacyFields: Set<String> = setOf("typeName", "isNullable", "isEnumerable", "elementTypeName"),
        allowMaps: Boolean
    ) {
        nodes.forEachIndexed { index, node ->
            val nodePath = "$path[$index]"
            validateCanonicalNode(node, "shape", legacyFields, nodePath, source)
            validateSupportedShape(node.path("shape"), "$nodePath.shape", source, allowMaps)
        }
    }

    private fun validateSupportedShape(
        shape: JsonNode,
        path: String,
        source: String,
        allowMaps: Boolean,
        mapEntry: Boolean = false,
        requireNonNull: Boolean = false
    ) {
        val kind = shape.path("kind").takeIf(JsonNode::isTextual)?.textValue()
            ?: throw GradleException("Arc artifact manifest in $source has no type-shape kind at $path.")
        if (requireNonNull && shape.path("nullable").asBoolean(false)) {
            throw GradleException("Arc artifact manifest in $source has a nullable container entry at $path.")
        }
        when (kind) {
            "VALUE" -> if (mapEntry) {
                val typeName = shape.path("typeName").takeIf(JsonNode::isTextual)?.textValue()
                if (typeName !in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
                    throw GradleException(
                        "Arc artifact manifest in $source has unsupported map value leaf '$typeName' at $path."
                    )
                }
            }
            "SEQUENCE" -> validateSupportedShape(
                shape.path("elementShape"),
                "$path.element",
                source,
                allowMaps,
                mapEntry = mapEntry,
                requireNonNull = true
            )
            "MAP" -> {
                if (!allowMaps) {
                    throw GradleException("Arc artifact manifest in $source uses a map in unsupported context $path.")
                }
                val key = shape.path("keyShape")
                val keyType = key.path("typeName").takeIf(JsonNode::isTextual)?.textValue()
                if (shape.path("keyCodec").asText() != "STRING" || key.path("kind").asText() != "VALUE" ||
                    key.path("nullable").asBoolean(false) || keyType !in MAP_STRING_TYPE_NAMES
                ) {
                    throw GradleException(
                        "Arc artifact manifest in $source requires nonnullable String map keys at $path.key."
                    )
                }
                validateSupportedShape(
                    shape.path("valueShape"),
                    "$path.value",
                    source,
                    allowMaps = true,
                    mapEntry = true,
                    requireNonNull = true
                )
            }
            else -> throw GradleException("Arc artifact manifest in $source has unknown type-shape kind '$kind' at $path.")
        }
    }

    private fun validateCanonicalNode(
        node: JsonNode,
        canonicalField: String,
        legacyFields: Set<String>,
        path: String,
        source: String
    ) {
        rejectLegacyFields(node, legacyFields, path, canonicalField, source)
        if (!node.hasNonNull(canonicalField)) {
            throw GradleException(
                "Arc artifact manifest in $source is missing canonical $canonicalField metadata at $path for " +
                    "formatVersion=${ArcArtifactManifest.CURRENT_FORMAT_VERSION}."
            )
        }
    }

    private fun rejectLegacyFields(
        node: JsonNode,
        legacyFields: Set<String>,
        path: String,
        canonicalField: String,
        source: String
    ) {
        val presentLegacyFields = legacyFields.filter(node::has)
        if (presentLegacyFields.isNotEmpty()) {
            throw GradleException(
                "Arc artifact manifest in $source contains legacy field(s) " +
                    "${presentLegacyFields.joinToString()} at $path; formatVersion=" +
                    "${ArcArtifactManifest.CURRENT_FORMAT_VERSION} requires canonical $canonicalField metadata only."
            )
        }
    }

    private fun validate(discovered: List<DiscoveredArcManifest>) {
        discovered.groupBy { it.manifest.moduleName }.filterValues { it.size > 1 }.forEach { (module, manifests) ->
            throw GradleException(
                "Arc artifact manifest module collision for '$module': " + manifests.joinToString { it.source }
            )
        }
    }
}
