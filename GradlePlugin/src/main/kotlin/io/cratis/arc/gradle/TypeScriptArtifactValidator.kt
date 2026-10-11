// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.ValidationRuleDescriptor
import java.io.File
import org.gradle.api.GradleException

/** The validation facts of one command property, model property or query parameter. */
private class MemberMetadata(
    val identity: String,
    val rules: List<ValidationRuleDescriptor>,
    val validateRecursively: Boolean,
    val isEnumerable: Boolean,
    val typeName: String,
    val elementTypeName: String?
)

private fun PropertyDescriptor.metadata(owner: String) =
    MemberMetadata("$owner.$name", validationRules, validateRecursively, isEnumerable, typeName, elementTypeName)

private fun ParameterDescriptor.metadata(owner: String) =
    MemberMetadata("$owner.$name", validationRules, validateRecursively, isEnumerable, typeName, elementTypeName)

/** The TypeScript types an artifact references and the global constructors its generated code uses at runtime. */
private class RuntimeUsage(
    val types: List<TypeScriptType>,
    val runtimeTypes: List<TypeScriptType>,
    val additionalGlobalRuntimeConstructors: Set<String>
)

/** Rejects artifact sets and configuration that would produce unsafe, colliding or uncompilable TypeScript. */
internal class TypeScriptArtifactValidator(
    private val model: TypeScriptGenerationModel,
    private val resolver: TypeScriptTypeResolver
) {
    private val artifacts = model.artifacts
    private val options = model.options
    private val shared = model.shared
    private val targets = model.targets

    fun validate() {
        validateMappings()
        validateArtifacts()
        validateSharedGeneration()
    }

    private fun validateMappings() {
        options.typeMappings.forEach { (sourceName, mapping) -> validateTypeMapping(sourceName, mapping) }
        options.packageMappings.forEach { (prefix, npmPackage) ->
            if (!JVM_TYPE_NAME.matches(prefix)) throw GradleException("Invalid Arc proxy package prefix '$prefix'.")
            resolver.mappedType("$prefix.Model", "Model", npmPackage)
        }
    }

    private fun validateTypeMapping(sourceName: String, mapping: ProxyTypeMapping) {
        if (!JVM_TYPE_NAME.matches(sourceName) || sourceName != mapping.typeName) {
            throw GradleException("Invalid Arc proxy mapping source '$sourceName'; use a fully qualified JVM type name.")
        }
        if (sourceName in model.conceptsBySource || sourceName in model.commandTypeNames) {
            throw GradleException("Arc proxy mapping '$sourceName' targets a concept or command; map a concept's underlying type or a command's properties/response instead.")
        }
        resolver.mappedType(sourceName, mapping.typeScriptType, mapping.npmPackage)
    }

    private fun validateArtifacts() {
        targets.forEach(::validateTarget)
        targets.groupBy(ArtifactTarget::relativePath).filterValues { it.size > 1 }.forEach { (path, collisions) ->
            throw GradleException("Duplicate TypeScript output '$path' for ${collisions.joinToString { it.sourceName }}.")
        }
        artifacts.commands.forEach { command -> validateCommand(command) }
        artifacts.queries.forEach { query -> validateQuery(query) }
        model.emittedTypes.forEach { type -> validateType(type) }
        model.emittedInterfaces.forEach { interfaceDescriptor -> validateInterface(interfaceDescriptor) }
    }

    private fun validateCommand(command: io.cratis.arc.metadata.CommandDescriptor) {
        val current = model.targetBySource.getValue(command.typeName)
        command.properties.forEach { property -> validateMemberMetadata(property.metadata(command.typeName)) }
        val referencedTypes = command.properties.map { resolver.resolvePropertyType(it, current) } +
            listOfNotNull(command.responseTypeName?.let { resolver.resolveType(it, current) })
        validateImportedTypeNames(
            current,
            referencedTypes,
            referencedTypes,
            if (command.responseTypeName == null) setOf("Object") else emptySet()
        )
    }

    private fun validateQuery(query: io.cratis.arc.metadata.QueryDescriptor) {
        val target = model.queryTarget(query)
        val clientParameters = query.parameters.filter { parameter -> parameter.source == QueryParameterSource.CLIENT }
        clientParameters.forEach { parameter -> validateMemberMetadata(parameter.metadata(query.fullyQualifiedName)) }
        val referencedTypes = listOf(resolver.resolveType(query.returnTypeName, target)) +
            clientParameters.map { resolver.resolveParameterType(it, target) }
        validateImportedTypeNames(target, referencedTypes, referencedTypes)
    }

    private fun validateType(type: io.cratis.arc.metadata.TypeDescriptor) {
        val current = model.targetBySource.getValue(type.fullyQualifiedName)
        type.properties.forEach { property -> validateMemberMetadata(property.metadata(type.fullyQualifiedName)) }
        val referencedTypes = type.properties.map { resolver.resolvePropertyType(it, current) } +
            type.properties.flatMap { resolver.resolveDerivatives(it, current) } +
            listOfNotNull(type.baseTypeName?.let { resolver.resolveBaseType(it, current) })
        validateImportedTypeNames(current, referencedTypes, referencedTypes)
    }

    private fun validateInterface(interfaceDescriptor: io.cratis.arc.metadata.InterfaceDescriptor) {
        val current = model.targetBySource.getValue(interfaceDescriptor.fullyQualifiedName)
        validateImportedTypeNames(
            current,
            interfaceDescriptor.properties.map { resolver.resolvePropertyType(it, current) }
        )
    }

    private fun validateSharedGeneration() {
        validateSharedValidatorNames()
        if (!options.endpointOptions.enableQueryHttpMethod && artifacts.queries.any { query -> reachesShared(query) }) {
            throw GradleException("[ARCVALIDATION_GRAPH] Shared model query arguments require enabled RFC QUERY transport.")
        }
        artifacts.types.filter { shared.contains(it.fullyQualifiedName) }.forEach { type ->
            if (model.isExternallyMapped(type.fullyQualifiedName)) {
                throw GradleException("[ARCVALIDATION_GRAPH] Shared model '${type.fullyQualifiedName}' cannot be externally mapped; generate its validator locally.")
            }
        }
    }

    private fun reachesShared(query: io.cratis.arc.metadata.QueryDescriptor): Boolean =
        query.parameters.any { parameter -> shared.reaches(parameter.shape) }

    private fun validateSharedValidatorNames() {
        val sharedTargets = targets.filter { target ->
            shared.contains(target.sourceName) ||
                target.kind == ArtifactKind.QUERY && reachesShared(artifacts.queries.single { it.fullyQualifiedName == target.sourceName })
        }
        sharedTargets.forEach { target ->
            if (targets.any { it.sourceName != target.sourceName && it.typeScriptName == target.typeScriptName + "Validator" }) {
                throw GradleException("[ARCVALIDATION_GRAPH] Generated validator '${target.typeScriptName}Validator' collides with another artifact; rename the artifact.")
            }
        }
    }

    private fun validateMemberMetadata(member: MemberMetadata) {
        member.rules.forEach { rule -> validateRuleArguments(member.identity, rule) }
        if (!member.validateRecursively) return
        val nestedType = if (member.isEnumerable) member.elementTypeName else member.typeName
        if (nestedType == null || !isGeneratedModelOrConcept(nestedType)) {
            throw GradleException(
                "Recursive Arc validation on '${member.identity}' requires a generated concrete model or concept type."
            )
        }
    }

    private fun isGeneratedModelOrConcept(typeName: String): Boolean =
        artifacts.types.any { type -> type.fullyQualifiedName == typeName } || model.conceptsBySource.containsKey(typeName)

    private fun validateRuleArguments(identity: String, rule: ValidationRuleDescriptor) {
        if (hasValidArguments(rule)) return
        throw GradleException(
            "Arc validation rule '${rule.ruleName}' on '$identity' has unsupported arguments ${rule.arguments}."
        )
    }

    private fun hasValidArguments(rule: ValidationRuleDescriptor): Boolean = when (rule.ruleName) {
        "notNull", "notEmpty", "emailAddress", "phone", "url", "creditCard" -> rule.arguments.isEmpty()
        "minLength", "maxLength", "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual" ->
            rule.arguments.size == 1 && rule.arguments.single() is Number
        "length" -> rule.arguments.size == 2 && rule.arguments.all { argument -> argument is Number }
        "matches" -> rule.arguments.size == 1 && rule.arguments.single() is String
        else -> false
    }

    private fun validateTarget(target: ArtifactTarget) {
        target.directory.forEach { segment ->
            if (isUnsafeSegment(segment)) {
                throw GradleException("Unsafe Arc proxy path segment '$segment' from '${target.sourceName}'.")
            }
        }
        if (!TYPESCRIPT_IDENTIFIER.matches(target.typeScriptName)) {
            throw GradleException(
                "Unsafe Arc proxy TypeScript name '${target.typeScriptName}' from '${target.sourceName}'."
            )
        }
        validateDestination(target)
    }

    private fun isUnsafeSegment(segment: String): Boolean =
        !SAFE_PATH_SEGMENT.matches(segment) || segment == "." || segment == ".."

    private fun validateDestination(target: ArtifactTarget) {
        val root = options.outputDirectory.canonicalFile.toPath()
        val destination = File(options.outputDirectory, target.relativePath).canonicalFile.toPath()
        if (!destination.startsWith(root)) {
            throw GradleException("Arc proxy output '${target.relativePath}' escapes '${options.outputDirectory.path}'.")
        }
    }

    private fun validateImportedTypeNames(
        current: ArtifactTarget,
        types: List<TypeScriptType>,
        runtimeTypes: List<TypeScriptType> = emptyList(),
        additionalGlobalRuntimeConstructors: Set<String> = emptySet()
    ) {
        val imported = types.flatMap { type -> type.recursiveTypes() }.mapNotNull(TypeScriptType::target)
            .filterNot { it.sourceName == current.sourceName }.distinct()
        imported.groupBy(ArtifactTarget::typeScriptName).filterValues { it.size > 1 }.forEach { (name, collisions) ->
            throw GradleException(
                "Duplicate TypeScript name '$name' imported by '${current.sourceName}' from " +
                    collisions.joinToString { it.sourceName } + "."
            )
        }
        imported.singleOrNull { it.typeScriptName == current.typeScriptName }?.let {
            throw GradleException(
                "Duplicate TypeScript name '${current.typeScriptName}' in '${current.sourceName}' and '${it.sourceName}'."
            )
        }
        val valueImports = types.flatMap { type -> type.recursiveTypes() }
            .mapNotNull(TypeScriptType::valueImport).distinct()
        validateFrameworkNames(current, imported, valueImports)
        validateValueImportNames(current, imported, valueImports)
        validateGlobalRuntimeConstructors(
            current,
            RuntimeUsage(types, runtimeTypes, additionalGlobalRuntimeConstructors),
            valueImports
        )
    }

    private fun validateFrameworkNames(
        current: ArtifactTarget,
        imported: List<ArtifactTarget>,
        valueImports: List<TypeScriptValueImport>
    ) {
        val sharedFrameworkNames = if (shared.contains(current.sourceName)) setOf("ArcSharedValidator", "ArcSharedResult", "ArcSharedSeverity", "${current.typeScriptName}Validator") else emptySet()
        if (current.typeScriptName in sharedFrameworkNames || imported.any { it.typeScriptName in sharedFrameworkNames })
            throw GradleException("[ARCVALIDATION_GRAPH] Shared validator scaffolding name collision in '${current.sourceName}'; rename the model.")
        val frameworkNames = sharedFrameworkNames + when (current.kind) {
            ArtifactKind.COMMAND -> setOf("Command", "CommandValidator", "PropertyDescriptor", "useCommand", "SetCommandValues", "ClearCommandValues", "I${current.typeScriptName}", "${current.typeScriptName}Validator")
            ArtifactKind.QUERY -> setOf("QueryFor", "ObservableQueryFor", "QueryResultWithState", "QueryValidator", "ParameterDescriptor", "Sorting", "Paging", "SortingActions", "SortingActionsForQuery", "SortingActionsForObservableQuery", "QueryHttpMethod", "QueryWhen", "ObservableQueryWhen", "ChangeSet", "PerformQuery", "SetSorting", "SetPage", "SetPageSize", "useQuery", "useQueryWithPaging", "useSuspenseQuery", "useSuspenseQueryWithPaging", "useObservableQuery", "useObservableQueryWithPaging", "useSuspenseObservableQuery", "useSuspenseObservableQueryWithPaging", "useChangeStream", "${current.typeScriptName}Parameters", "${current.typeScriptName}Validator", "${current.typeScriptName}SortBy", "${current.typeScriptName}SortByWithoutQuery")
            ArtifactKind.TYPE -> setOf("field", "derivedType")
            else -> emptySet()
        }
        valueImports.firstOrNull { it.name in frameworkNames }?.let { reference ->
            throw GradleException("External TypeScript import '${reference.name}' in '${current.sourceName}' collides with generated framework scaffolding.")
        }
    }

    private fun validateValueImportNames(
        current: ArtifactTarget,
        imported: List<ArtifactTarget>,
        valueImports: List<TypeScriptValueImport>
    ) {
        valueImports.groupBy(TypeScriptValueImport::name).filterValues { imports ->
            imports.map(TypeScriptValueImport::packageName).distinct().size > 1
        }.forEach { (name, collisions) ->
            throw GradleException(
                "Duplicate TypeScript value import '$name' in '${current.sourceName}' from " +
                    collisions.joinToString { it.packageName } + "."
            )
        }
        valueImports.forEach { valueImport ->
            if (valueImport.name == current.typeScriptName) {
                throw GradleException(
                    "Duplicate TypeScript name '${current.typeScriptName}' in '${current.sourceName}' and " +
                        "value import '${valueImport.packageName}'."
                )
            }
            imported.firstOrNull { it.typeScriptName == valueImport.name }?.let { artifact ->
                throw GradleException(
                    "Duplicate TypeScript name '${valueImport.name}' imported by '${current.sourceName}' from " +
                        "'${artifact.sourceName}' and '${valueImport.packageName}'."
                )
            }
        }
    }

    private fun validateGlobalRuntimeConstructors(
        current: ArtifactTarget,
        usage: RuntimeUsage,
        valueImports: List<TypeScriptValueImport>
    ) {
        val globalRuntimeConstructors = usage.runtimeTypes.mapNotNull { it.globalRuntimeConstructor() }.toSet() +
            usage.additionalGlobalRuntimeConstructors
        val valueImportedTargets = customImports(usage.types, usage.runtimeTypes)
            .filter { it.kind == TypeScriptImportKind.VALUE && it.target.sourceName != current.sourceName }
            .map(TypeScriptImport::target)
        globalRuntimeConstructors.sorted().forEach { constructor ->
            if (current.typeScriptName == constructor) {
                throw GradleException(
                    "TypeScript global runtime constructor '$constructor' in '${current.sourceName}' is shadowed by " +
                        "the generated artifact with the same name."
                )
            }
            valueImports.firstOrNull { it.name == constructor }?.let { reference ->
                throw GradleException("TypeScript global runtime constructor '$constructor' in '${current.sourceName}' is shadowed by the value import '${reference.packageName}'.")
            }
            valueImportedTargets.firstOrNull { it.typeScriptName == constructor }?.let { artifact ->
                throw GradleException(
                    "TypeScript global runtime constructor '$constructor' in '${current.sourceName}' is shadowed by " +
                        "the value import '${artifact.sourceName}'."
                )
            }
        }
    }
}
