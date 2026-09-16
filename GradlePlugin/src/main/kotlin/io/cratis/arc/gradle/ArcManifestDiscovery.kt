// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.DocumentationSummaries
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.TypeDescriptor
import java.io.File
import java.util.jar.JarFile
import org.gradle.api.GradleException

internal data class DiscoveredArcManifest(val source: String, val manifest: ArcArtifactManifest,
    val sharedValidators: List<SharedValidatorDescriptor> = emptyList())

internal data class MergedArcArtifacts(
    val commands: List<CommandDescriptor>,
    val queries: List<QueryDescriptor>,
    val types: List<TypeDescriptor>,
    val enums: List<EnumDescriptor>,
    val interfaces: List<InterfaceDescriptor> = emptyList(),
    val concepts: List<ConceptDescriptor> = emptyList(),
    val sharedValidators: List<SharedValidatorDescriptor> = emptyList()
)

internal object ArcManifestDiscovery {
    private const val MANIFEST_PREFIX = "META-INF/cratis/arc/"
    private val MAP_STRING_TYPE_NAMES = setOf("kotlin.String", "java.lang.String", "String")
    private val MAP_SAFE_PRIMITIVE_TYPE_NAMES = setOf(
        "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Int", "kotlin.Short", "kotlin.String",
        "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Integer", "java.lang.Short",
        "java.lang.String", "boolean", "byte", "char", "int", "short", "String", "Boolean"
    )

    fun discover(classpath: Iterable<File>, rootModule: String? = null): List<DiscoveredArcManifest> {
        val mapper = ArcObjectMapper.create()
        val discovered = mutableListOf<DiscoveredArcManifest>()
        classpath.map(File::getAbsoluteFile).distinctBy { it.normalize().path }.sortedBy(File::getPath).forEach { entry ->
            when {
                entry.isDirectory -> discoverDirectory(entry, mapper).forEach(discovered::add)
                entry.isFile && entry.extension.equals("jar", ignoreCase = true) -> discoverJar(entry, mapper).forEach(discovered::add)
            }
        }
        validate(discovered)
        val fluent = ArcFluentValidationMetadataDiscovery.inventory(classpath.filter(File::exists))
        if (fluent.validators.isNotEmpty()) {
            val scope = rootModule?.let(fluent.scopes::get)
            if (scope == null || !scope.indexed || scope.validators != fluent.classes) throw GradleException(
                "Shared fluent classpath has no complete verified root scope for '$rootModule'; use the Arc plugin or --module-name with a matching compiled root module and dependency index. " +
                    "Missing declarations: ${fluent.classes - scope?.validators.orEmpty()}.")
        }
        validateFluentAgreement(discovered, fluent)
        val shared = fluent.validators.values.map { declaration ->
            SharedValidatorDescriptor(declaration.path("validatorTypeName").asString(), declaration.path("modelTypeName").asString(),
                declaration.path("members").toList().map { member ->
                    SharedValidationMember(member.path("member").asString(), member.path("memberTypeName").asString(),
                        member.path("rules").toList().map { rule -> io.cratis.arc.metadata.ValidationRuleDescriptor(
                            rule.path("ruleName").asString(), rule.path("arguments").toList().map { argument ->
                                if (argument.isNumber) argument.numberValue() else argument.asString()
                            }, rule.path("message").takeUnless { it.isNull }?.asString()) })
                })
        }
        return discovered.sortedWith(compareBy({ it.manifest.moduleName }, { it.source })).map { it.copy(sharedValidators = shared) }
    }

    private fun validateFluentAgreement(discovered: List<DiscoveredArcManifest>, fluent: ArcFluentValidationMetadataDiscovery.Inventory) {
        fun arguments(values: List<Any>): List<String> = values.map { value ->
            if (value is Number) value.toString().toBigDecimal().stripTrailingZeros().toPlainString() else value.toString()
        }
        val represented = discovered.flatMap { document -> document.manifest.types.map { it.fullyQualifiedName } + document.manifest.commands.map { it.typeName } }.toSet()
        fluent.validators.values.forEach { declaration ->
            if (declaration.path("modelTypeName").asString() !in represented) throw GradleException(
                "Shared fluent model '${declaration.path("modelTypeName").asString()}' has no artifact metadata; rebuild its producer with Arc KSP.")
        }
        discovered.forEach documentLoop@ { document ->
            val models = document.manifest.types.map { it.fullyQualifiedName } + document.manifest.commands.map { it.typeName }
            val relevant = fluent.validators.filterValues { it.path("modelTypeName").asString() in models }
            if (relevant.isNotEmpty()) {
                val scope = fluent.scopes[document.manifest.moduleName]
                if (scope == null || !scope.indexed) {
                    if (document.manifest.moduleName !in fluent.documents) return@documentLoop // Legacy annotation-only dependency; the complete root was verified above.
                    throw GradleException("Shared fluent metadata in ${document.source} has no verified compiler scope; rebuild with the Arc fluent dependency-index task before generating clients.")
                }
                // A producer cannot know downstream validators. Verify what this compilation actually
                // promised; the shared declaration transport remains available for final graph composition.
                relevant.filterKeys { it in scope.validators }.values.forEach { declaration ->
                    val model = declaration.path("modelTypeName").asString()
                    val properties = document.manifest.types.filter { it.fullyQualifiedName == model }.map { it.properties } +
                        document.manifest.commands.filter { it.typeName == model }.map { it.properties }
                    properties.forEach { members -> declaration.path("members").forEach { expected ->
                        val member = expected.path("member").asString()
                        val actual = members.singleOrNull { it.name == member }
                        expected.path("rules").forEach { rule ->
                            val expectedArgs = rule.path("arguments").toList().map { if (it.isNumber) it.numberValue() else it.asString() }
                            val message = rule.path("message").takeUnless { it.isNull }?.asString()
                            if (actual == null || actual.validationRules.none { it.ruleName == rule.path("ruleName").asString() &&
                                    arguments(it.arguments) == arguments(expectedArgs) && it.message == message }) {
                                throw GradleException("Shared fluent rule for '$model.$member' is missing from ${document.source}; " +
                                    "recompile consumers with the verified fluent dependency index and matching compile/runtime dependencies.")
                            }
                        }
                    } }
                }
            }
        }
    }

    fun merge(discovered: Iterable<DiscoveredArcManifest>): MergedArcArtifacts {
        val snapshots = discovered.toList()
        val manifests = snapshots.map(DiscoveredArcManifest::manifest)
        return MergedArcArtifacts(
            manifests.flatMap(ArcArtifactManifest::commands).sortedBy(CommandDescriptor::typeName),
            manifests.flatMap(ArcArtifactManifest::queries).sortedBy(QueryDescriptor::fullyQualifiedName),
            manifests.flatMap(ArcArtifactManifest::types).groupBy(TypeDescriptor::fullyQualifiedName).toSortedMap()
                .map { (name, values) -> ValidationDescriptorMerge.merge(values, TypeDescriptor::class.java, name) },
            manifests.flatMap(ArcArtifactManifest::enums).sortedBy(EnumDescriptor::fullyQualifiedName),
            manifests.flatMap(ArcArtifactManifest::interfaces).groupBy(InterfaceDescriptor::fullyQualifiedName).toSortedMap()
                .map { (name, values) -> ValidationDescriptorMerge.merge(values, InterfaceDescriptor::class.java, name) },
            manifests.flatMap(ArcArtifactManifest::concepts)
                .sortedBy(ConceptDescriptor::fullyQualifiedName)
                .distinctBy(ConceptDescriptor::fullyQualifiedName),
            snapshots.flatMap { it.sharedValidators }.distinct().sortedBy { it.identity }
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
            validateSummary(command, "commands[$commandIndex]", source)
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
            validateSummary(query, "queries[$queryIndex]", source)
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
            validateSummary(type, "types[$typeIndex]", source)
            validateShapeNodes(type.path("properties"), "types[$typeIndex].properties", source, allowMaps = true)
        }
        root.path("interfaces").forEachIndexed { interfaceIndex, interfaceNode ->
            validateSummary(interfaceNode, "interfaces[$interfaceIndex]", source)
            validateShapeNodes(
                interfaceNode.path("properties"),
                "interfaces[$interfaceIndex].properties",
                source,
                allowMaps = true
            )
        }
        root.path("enums").forEachIndexed { enumIndex, enumNode ->
            validateSummary(enumNode, "enums[$enumIndex]", source)
        }
    }

    /**
     * Rejects a source documentation summary that a generated proxy could not render on one JSDoc line.
     *
     * The manifest is read from third-party jars, so the reader enforces the same invariant the writer applies rather
     * than trusting it.
     */
    private fun validateSummary(node: JsonNode, path: String, source: String) {
        val summary = node.get("summary") ?: return
        if (summary.isNull) return
        if (!summary.isString) {
            throw GradleException("Arc artifact manifest in $source must declare a textual summary at $path.")
        }
        try {
            DocumentationSummaries.validate(summary.stringValue(), path)
        } catch (exception: IllegalArgumentException) {
            throw GradleException(
                "Arc artifact manifest in $source has an unusable summary at $path: ${exception.message}",
                exception
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
            validateSummary(node, nodePath, source)
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
        val kind = shape.path("kind").takeIf(JsonNode::isString)?.stringValue()
            ?: throw GradleException("Arc artifact manifest in $source has no type-shape kind at $path.")
        if (requireNonNull && shape.path("nullable").asBoolean(false)) {
            throw GradleException("Arc artifact manifest in $source has a nullable container entry at $path.")
        }
        when (kind) {
            "VALUE" -> if (mapEntry) {
                val typeName = shape.path("typeName").takeIf(JsonNode::isString)?.stringValue()
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
                val keyType = key.path("typeName").takeIf(JsonNode::isString)?.stringValue()
                if (shape.path("keyCodec").asString() != "STRING" || key.path("kind").asString() != "VALUE" ||
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
