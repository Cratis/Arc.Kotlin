// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.json.ArcCamelCase
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.QueryTransportType
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.GradleException

internal data class ProxyGenerationOptions(
    val outputDirectory: File,
    val endpointOptions: ApiEndpointOptions,
    val removeStaleGeneratedFiles: Boolean,
    val segmentsToSkip: Int,
    /** Configured JVM type to TypeScript type mappings, keyed by fully qualified JVM type name. */
    val typeMappings: Map<String, ProxyTypeMapping> = emptyMap(),
    /** JVM package to npm package mappings; matching types are imported instead of generated. */
    val packageMappings: Map<String, String> = emptyMap()
)

internal class TypeScriptProxyGenerator(
    private val artifacts: MergedArcArtifacts,
    private val options: ProxyGenerationOptions,
    private val useProxyFileSuffix: Boolean
) {
    constructor(artifacts: MergedArcArtifacts, options: ProxyGenerationOptions) : this(artifacts, options, false)

    private val shared = SharedValidationGraph(artifacts)
    private val commandTypeNames = artifacts.commands.map(CommandDescriptor::typeName).toSet()

    /**
     * A type answered by configuration is never also generated.
     *
     * Emitting it as well would put a local declaration and an external import of the same name in scope, so a
     * configured mapping has to remove the type from output the way Arc .NET excludes types from a mapped assembly.
     */
    private fun isExternallyMapped(typeName: String): Boolean =
        typeName in options.typeMappings ||
            ProxyTypeMappings.npmPackageFor(typeName, options.packageMappings) != null

    private val emittedTypes = artifacts.types
        .filterNot { it.fullyQualifiedName in commandTypeNames }
        .filterNot { isExternallyMapped(it.fullyQualifiedName) }
    private val emittedInterfaces = artifacts.interfaces.filterNot { isExternallyMapped(it.fullyQualifiedName) }
    private val emittedEnums = artifacts.enums.filterNot { isExternallyMapped(it.fullyQualifiedName) }
    private val queryTargets = buildQueryTargets()
    private val conceptsBySource = artifacts.concepts.associateBy { concept -> concept.fullyQualifiedName }
    private val targets = buildTargets()
    private val targetBySource = targets.associateBy(ArtifactTarget::sourceName)
    private val validators = TypeScriptValidatorRenderer(shared)
    private val outputTree = TypeScriptOutputTree(options.outputDirectory)
    private val resolver = TypeScriptTypeResolver(artifacts, options, conceptsBySource, targetBySource)

    fun generate(): List<File> {
        require(options.segmentsToSkip >= 0) { "Proxy segmentsToSkip cannot be negative." }
        outputTree.validate()
        validateMappings()
        validateArtifacts()
        validateSharedGeneration()
        val generated = renderArtifacts()

        val written = outputTree.write(generated)
        val removed = if (options.removeStaleGeneratedFiles && options.outputDirectory.isDirectory) {
            outputTree.removeStale(generated.map(GeneratedFile::relativePath))
        } else {
            emptySet()
        }
        val indexes = outputTree.updateIndexFiles(generated.map(GeneratedFile::relativePath).toSet(), removed)
        outputTree.removeEmptyDirectories()
        return written + indexes
    }

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

    private fun queryTarget(query: QueryDescriptor): ArtifactTarget = queryTargets.getValue(query.fullyQualifiedName)

    private fun outputDirectory(location: List<String>): List<String> = location.drop(options.segmentsToSkip)

    private fun validateMappings() {
        options.typeMappings.forEach { (sourceName, mapping) ->
            if (!JVM_TYPE_NAME.matches(sourceName) || sourceName != mapping.typeName) {
                throw GradleException("Invalid Arc proxy mapping source '$sourceName'; use a fully qualified JVM type name.")
            }
            if (sourceName in conceptsBySource || sourceName in commandTypeNames) {
                throw GradleException("Arc proxy mapping '$sourceName' targets a concept or command; map a concept's underlying type or a command's properties/response instead.")
            }
            resolver.mappedType(sourceName, mapping.typeScriptType, mapping.npmPackage)
        }
        options.packageMappings.forEach { (prefix, npmPackage) ->
            if (!JVM_TYPE_NAME.matches(prefix)) throw GradleException("Invalid Arc proxy package prefix '$prefix'.")
            resolver.mappedType("$prefix.Model", "Model", npmPackage)
        }
    }

    private fun validateArtifacts() {
        targets.forEach(::validateTarget)
        targets.groupBy(ArtifactTarget::relativePath).filterValues { it.size > 1 }.forEach { (path, collisions) ->
            throw GradleException("Duplicate TypeScript output '$path' for ${collisions.joinToString { it.sourceName }}.")
        }
        artifacts.commands.forEach { command ->
            val current = targetBySource.getValue(command.typeName)
            command.properties.forEach { property ->
                validateValidationMetadata(
                    "${command.typeName}.${property.name}",
                    property.validationRules,
                    property.validateRecursively,
                    property.isEnumerable,
                    property.typeName,
                    property.elementTypeName
                )
            }
            val referencedTypes = command.properties.map { resolvePropertyType(it, current) } +
                listOfNotNull(command.responseTypeName?.let { resolver.resolveType(it, current) })
            validateImportedTypeNames(
                current,
                referencedTypes,
                referencedTypes,
                if (command.responseTypeName == null) setOf("Object") else emptySet()
            )
        }
        artifacts.queries.forEach { query ->
            val target = queryTarget(query)
            query.parameters.filter { parameter -> parameter.source == QueryParameterSource.CLIENT }.forEach { parameter ->
                validateValidationMetadata(
                    "${query.fullyQualifiedName}.${parameter.name}",
                    parameter.validationRules,
                    parameter.validateRecursively,
                    parameter.isEnumerable,
                    parameter.typeName,
                    parameter.elementTypeName
                )
            }
            val referencedTypes = listOf(resolver.resolveType(query.returnTypeName, target)) +
                query.parameters.filter { parameter -> parameter.source == QueryParameterSource.CLIENT }
                    .map { resolveParameterType(it, target) }
            validateImportedTypeNames(target, referencedTypes, referencedTypes)
        }
        emittedTypes.forEach { type ->
            val current = targetBySource.getValue(type.fullyQualifiedName)
            type.properties.forEach { property ->
                validateValidationMetadata(
                    "${type.fullyQualifiedName}.${property.name}",
                    property.validationRules,
                    property.validateRecursively,
                    property.isEnumerable,
                    property.typeName,
                    property.elementTypeName
                )
            }
            val referencedTypes = type.properties.map { resolvePropertyType(it, current) } +
                type.properties.flatMap { resolveDerivatives(it, current) } +
                listOfNotNull(type.baseTypeName?.let { resolveBaseType(it, current) })
            validateImportedTypeNames(current, referencedTypes, referencedTypes)
        }
        emittedInterfaces.forEach { interfaceDescriptor ->
            val current = targetBySource.getValue(interfaceDescriptor.fullyQualifiedName)
            validateImportedTypeNames(
                current,
                interfaceDescriptor.properties.map { resolvePropertyType(it, current) }
            )
        }
    }

    private fun validateSharedGeneration() {
        val sharedTargets = targets.filter { shared.contains(it.sourceName) || it.kind == ArtifactKind.QUERY &&
            artifacts.queries.single { query -> query.fullyQualifiedName == it.sourceName }.parameters.any { parameter -> shared.reaches(parameter.shape) } }
        sharedTargets.forEach { target ->
            if (targets.any { it.sourceName != target.sourceName && it.typeScriptName == target.typeScriptName + "Validator" })
                throw GradleException("[ARCVALIDATION_GRAPH] Generated validator '${target.typeScriptName}Validator' collides with another artifact; rename the artifact.")
        }
        if (!options.endpointOptions.enableQueryHttpMethod && artifacts.queries.any { query -> query.parameters.any { shared.reaches(it.shape) } })
            throw GradleException("[ARCVALIDATION_GRAPH] Shared model query arguments require enabled RFC QUERY transport.")
        artifacts.types.filter { shared.contains(it.fullyQualifiedName) }.forEach { type ->
            if (isExternallyMapped(type.fullyQualifiedName)) throw GradleException("[ARCVALIDATION_GRAPH] Shared model '${type.fullyQualifiedName}' cannot be externally mapped; generate its validator locally.")
        }
    }

    private fun renderArtifacts(): List<GeneratedFile> {
        val generated = mutableListOf<GeneratedFile>()
        val commandNamespaces = EndpointRouteHelper.groupByNamespace(
            artifacts.commands,
            CommandDescriptor::location,
            options.endpointOptions.segmentsToSkipForRoute
        )
        val queryNamespaces = EndpointRouteHelper.groupByNamespace(
            artifacts.queries,
            QueryDescriptor::location,
            options.endpointOptions.segmentsToSkipForRoute
        )
        artifacts.commands.forEach { command ->
            val target = targetBySource.getValue(command.typeName)
            val namespace = command.location.drop(options.endpointOptions.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.commandRoute(
                command,
                options.endpointOptions,
                (commandNamespaces[namespace]?.size ?: 0) > 1
            )
            generated += GeneratedFile(target.relativePath, command.typeName, renderCommand(command, target, route))
        }
        artifacts.queries.forEach { query ->
            val target = queryTarget(query)
            val namespace = query.location.drop(options.endpointOptions.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.queryRoute(
                query,
                options.endpointOptions,
                (queryNamespaces[namespace]?.size ?: 0) > 1
            )
            generated += GeneratedFile(target.relativePath, query.declaringTypeName, renderQuery(query, target, route))
        }
        emittedTypes.forEach { type ->
            val target = targetBySource.getValue(type.fullyQualifiedName)
            generated += GeneratedFile(target.relativePath, type.fullyQualifiedName, renderType(type, target))
        }
        emittedInterfaces.forEach { interfaceDescriptor ->
            val target = targetBySource.getValue(interfaceDescriptor.fullyQualifiedName)
            generated += GeneratedFile(
                target.relativePath,
                interfaceDescriptor.fullyQualifiedName,
                renderInterface(interfaceDescriptor, target)
            )
        }
        emittedEnums.forEach { enum ->
            val target = targetBySource.getValue(enum.fullyQualifiedName)
            generated += GeneratedFile(target.relativePath, enum.fullyQualifiedName, renderEnum(enum))
        }
        return generated
    }

    private fun validateValidationMetadata(
        identity: String,
        rules: List<ValidationRuleDescriptor>,
        validateRecursively: Boolean,
        isEnumerable: Boolean,
        typeName: String,
        elementTypeName: String?
    ) {
        rules.forEach { rule ->
            val validArguments = when (rule.ruleName) {
                "notNull", "notEmpty", "emailAddress", "phone", "url", "creditCard" -> rule.arguments.isEmpty()
                "minLength", "maxLength", "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual" ->
                    rule.arguments.size == 1 && rule.arguments.single() is Number
                "length" -> rule.arguments.size == 2 && rule.arguments.all { argument -> argument is Number }
                "matches" -> rule.arguments.size == 1 && rule.arguments.single() is String
                else -> false
            }
            if (!validArguments) {
                throw GradleException(
                    "Arc validation rule '${rule.ruleName}' on '$identity' has unsupported arguments ${rule.arguments}."
                )
            }
        }
        if (validateRecursively) {
            val nestedType = if (isEnumerable) elementTypeName else typeName
            val isGeneratedModel = artifacts.types.any { type -> type.fullyQualifiedName == nestedType }
            val isConcept = conceptsBySource.containsKey(nestedType)
            if (nestedType == null || (!isGeneratedModel && !isConcept)) {
                throw GradleException(
                    "Recursive Arc validation on '$identity' requires a generated concrete model or concept type."
                )
            }
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
        validateGlobalRuntimeConstructors(current, types, runtimeTypes, additionalGlobalRuntimeConstructors, valueImports)
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
        types: List<TypeScriptType>,
        runtimeTypes: List<TypeScriptType>,
        additionalGlobalRuntimeConstructors: Set<String>,
        valueImports: List<TypeScriptValueImport>
    ) {
        val globalRuntimeConstructors = runtimeTypes.mapNotNull { it.globalRuntimeConstructor() }.toSet() +
            additionalGlobalRuntimeConstructors
        val valueImportedTargets = customImports(types, runtimeTypes)
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

    private fun renderCommand(command: CommandDescriptor, target: ArtifactTarget, route: String): String {
        val propertyTypes = command.properties.map { resolvePropertyType(it, target) }
        val response = command.responseTypeName?.let { resolver.resolveType(it, target) }
        val referencedTypes = propertyTypes + listOfNotNull(response)
        val imports = customImports(referencedTypes, referencedTypes)
        val interfaceName = "I${command.name}"
        val validationTargets = command.properties.map { property ->
            ValidationTarget(lowerCamel(property.name), property.validationRules.clientRepresentable().filterNot { rule -> shared.rules(command.typeName, property.name).any { sameRule(rule, it) } })
        }.filter(ValidationTarget::hasRules).sortedBy(ValidationTarget::propertyName)
        val sharedValidation = shared.contains(command.typeName)
        val hasValidation = validationTargets.isNotEmpty() || sharedValidation
        val requestParameters = routeParameters(route, command.properties)
        val requiresStringMapGuard = command.properties.any { property -> property.shape.containsMap() }
        return buildString {
            appendGeneratedHeader()
            append("/* eslint-disable sort-imports */\n")
            append("/* eslint-disable @typescript-eslint/no-empty-interface */\n")
            append("// eslint-disable-next-line header/header\n")
            append("import { Command${if (hasValidation) ", CommandValidator" else ""} } from '@cratis/arc/commands';\n")
            append("import { useCommand, type SetCommandValues, type ClearCommandValues } from '@cratis/arc.react/commands';\n")
            append("import { PropertyDescriptor } from '@cratis/arc/reflection';\n")
            appendPackageImports(referencedTypes)
            appendCustomImports(imports, target)
            if (sharedValidation) appendSharedImports(target, command.properties.filterNot { it.ignoreValidation }.map { it.shape })
            append("\n")
            if (requiresStringMapGuard) {
                appendStringMapGuard()
            }
            appendDocumentation(command.summary)
            append("export interface $interfaceName {\n")
            command.properties.forEach { property ->
                appendDocumentation(property.summary, "    ")
                append("    ${lowerCamel(property.name)}?: ${propertyTypeName(property, target)};\n")
            }
            append("}\n\n")
            if (sharedValidation) {
                validators.appendSharedValidator(
                    this,
                    SharedValidatorSpec(
                        "${command.name}Validator", "CommandValidator", interfaceName, command.typeName,
                        command.properties, validationTargets, target
                    )
                )
            } else {
                validators.appendValidator(this, "${command.name}Validator", "CommandValidator", interfaceName, validationTargets)
            }
            val responseGeneric = response?.let {
                ", ${it.name}${if (command.responseIsEnumerable) "[]" else ""}"
            }.orEmpty()
            appendDocumentation(command.summary)
            append("export class ${command.name} extends Command<$interfaceName$responseGeneric> implements $interfaceName {\n")
            append("    readonly route: string = '${escape(route)}';\n")
            if (hasValidation) {
                append("    readonly validation: CommandValidator = new ${command.name}Validator();\n")
            }
            append("    readonly treatWarningsAsErrors: boolean = ${command.treatWarningsAsErrors};\n")
            append("    readonly roles: string[] = ${roles(command.authorization.roles)};\n")
            append("    readonly propertyDescriptors: PropertyDescriptor[] = [\n")
            command.properties.forEach { property ->
                val type = resolvePropertyType(property, target)
                append("        new PropertyDescriptor('${lowerCamel(property.name)}', ${type.constructor}, ${property.isNullable}),\n")
            }
            append("    ];\n\n")
            command.properties.forEach { property ->
                val nullable = if (property.isNullable) "?" else "!"
                append("    private _${lowerCamel(property.name)}$nullable: ${propertyTypeName(property, target)};\n")
            }
            append("\n    constructor() {\n")
            append("        super(${response?.constructor ?: "Object"}, ${command.responseIsEnumerable});\n")
            append("    }\n\n")
            append("    get requestParameters(): string[] {\n        return [\n")
            requestParameters.forEach { append("            '$it',\n") }
            append("        ];\n    }\n\n")
            command.properties.forEach { property ->
                val name = lowerCamel(property.name)
                val type = propertyTypeName(property, target)
                val optional = if (property.isNullable) " | undefined" else ""
                appendDocumentation(property.summary, "    ")
                append("    get $name(): $type$optional {\n        return this._$name;\n    }\n\n")
                append("    set $name(value: $type$optional) {\n")
                if (property.shape.containsMap()) {
                    append("        this._$name = sanitizeArcStringMap(value, '$name');\n")
                } else {
                    append("        this._$name = value;\n")
                }
                append("        this.propertyChanged('$name');\n    }\n")
            }
            append("\n    static use(initialValues?: $interfaceName): [${command.name}, SetCommandValues<$interfaceName>, ClearCommandValues] {\n")
            append("        // eslint-disable-next-line @typescript-eslint/ban-ts-comment\n        // @ts-ignore\n")
            append("        return useCommand<${command.name}, $interfaceName>(${command.name}, initialValues);\n    }\n}\n")
        }
    }

    private fun StringBuilder.appendStringMapGuard() {
        append("const reservedArcStringMapKeys = new Set(['__proto__', 'prototype', 'constructor']);\n\n")
        append("function sanitizeArcStringMap<T>(value: T, path: string, seen = new WeakMap<object, unknown>()): T {\n")
        append("    if (value === null || typeof value !== 'object') return value;\n")
        append("    const previous = seen.get(value);\n")
        append("    if (previous !== undefined) return previous as T;\n")
        append("    if (Array.isArray(value)) {\n")
        append("        if (Object.getPrototypeOf(value) !== Array.prototype) {\n")
        append("            throw new TypeError(`String map sequence at '\${path}' must use Array.prototype.`);\n")
        append("        }\n")
        append("        for (const key of Object.getOwnPropertyNames(value)) {\n")
        append("            if (reservedArcStringMapKeys.has(key)) {\n")
        append("                throw new TypeError(`String map sequence at '\${path}' contains reserved key '\${key}'.`);\n")
        append("            }\n")
        append("        }\n")
        append("        const normalized: unknown[] = [];\n")
        append("        seen.set(value, normalized);\n")
        append("        value.forEach((entry, index) => normalized.push(sanitizeArcStringMap(entry, `\${path}[\${index}]`, seen)));\n")
        append("        return normalized as T;\n")
        append("    }\n")
        append("    const prototype = Object.getPrototypeOf(value);\n")
        append("    if (prototype !== Object.prototype && prototype !== null) {\n")
        append("        throw new TypeError(`String map at '\${path}' must use Object.prototype or a null prototype.`);\n")
        append("    }\n")
        append("    for (const key of Object.getOwnPropertyNames(value)) {\n")
        append("        if (reservedArcStringMapKeys.has(key)) {\n")
        append("            throw new TypeError(`String map at '\${path}' contains reserved key '\${key}'.`);\n")
        append("        }\n")
        append("    }\n")
        append("    const normalized: Record<string, unknown> = {};\n")
        append("    seen.set(value, normalized);\n")
        append("    for (const key of Object.keys(value)) {\n")
        append("        normalized[key] = sanitizeArcStringMap((value as Record<string, unknown>)[key], `\${path}.\${key}`, seen);\n")
        append("    }\n")
        append("    return normalized as T;\n")
        append("}\n\n")
    }

    private fun TypeShapeDescriptor.containsMap(): Boolean = when (kind) {
        TypeShapeKind.VALUE -> false
        TypeShapeKind.SEQUENCE -> requireNotNull(elementShape).containsMap()
        TypeShapeKind.MAP -> true
    }

    private fun renderType(type: TypeDescriptor, target: ArtifactTarget): String {
        val propertyTypes = type.properties.associateWith { property -> resolvePropertyType(property, target) }
        val derivativeTypes = type.properties.associateWith { property -> resolveDerivatives(property, target) }
        val baseType = type.baseTypeName?.let { resolveBaseType(it, target) }
        val referencedTypes = propertyTypes.values + derivativeTypes.values.flatten() + listOfNotNull(baseType)
        val imports = customImports(referencedTypes, referencedTypes)
        return buildString {
            appendGeneratedHeader()
            append("/* eslint-disable sort-imports */\n")
            append("// eslint-disable-next-line header/header\n")
            val fundamentals = buildList {
                if (type.properties.isNotEmpty()) add(TypeScriptValueImport(FUNDAMENTALS_PACKAGE, "field"))
                if (type.derivedTypeId != null) add(TypeScriptValueImport(FUNDAMENTALS_PACKAGE, "derivedType"))
            }
            appendPackageImports(referencedTypes, fundamentals)
            appendCustomImports(imports, target)
            if (shared.contains(type.fullyQualifiedName)) appendSharedImports(target, type.properties.filterNot { it.ignoreValidation }.map { it.shape })
            append("\n")
            appendDocumentation(type.summary)
            type.derivedTypeId?.let { append("@derivedType('${escape(it)}')\n") }
            append("export class ${type.name}${baseType?.let { " extends ${it.name}" }.orEmpty()} {\n")
            type.properties.forEach { property ->
                val resolved = propertyTypes.getValue(property)
                val derivatives = derivativeTypes.getValue(property)
                val fieldArguments = buildList {
                    add(resolved.constructor)
                    if (property.isEnumerable || derivatives.isNotEmpty()) add(property.isEnumerable.toString())
                    if (derivatives.isNotEmpty()) add("[${derivatives.joinToString(", ") { it.constructor }}]")
                }
                appendDocumentation(property.summary, "    ")
                append("    @field(${fieldArguments.joinToString(", ")})\n")
                val marker = if (property.isNullable) "?" else "!"
                append("    ${lowerCamel(property.name)}$marker: ${propertyTypeName(property, target)};\n")
            }
            append("}\n")
            if (shared.contains(type.fullyQualifiedName)) {
                append("\n")
                validators.appendSharedValidator(
                    this,
                    SharedValidatorSpec(
                        "${type.name}Validator", "ArcSharedValidator", type.name, type.fullyQualifiedName,
                        type.properties, emptyList(), target
                    )
                )
            }
        }
    }

    private fun renderInterface(interfaceDescriptor: InterfaceDescriptor, target: ArtifactTarget): String {
        val referencedTypes = interfaceDescriptor.properties.map { resolvePropertyType(it, target) }
        val imports = customImports(referencedTypes)
        return buildString {
            appendGeneratedHeader()
            append("/* eslint-disable sort-imports */\n")
            append("// eslint-disable-next-line header/header\n")
            appendPackageImports(referencedTypes)
            appendCustomImports(imports, target)
            append("\n")
            appendDocumentation(interfaceDescriptor.summary)
            append("export interface ${interfaceDescriptor.name} {\n")
            interfaceDescriptor.properties.forEach { property ->
                val optional = if (property.isNullable) "?" else ""
                appendDocumentation(property.summary, "    ")
                append("    ${lowerCamel(property.name)}$optional: ${propertyTypeName(property, target)};\n")
            }
            append("}\n")
        }
    }

    private fun renderEnum(enum: EnumDescriptor): String = buildString {
        appendGeneratedHeader()
        append("// eslint-disable-next-line header/header\n")
        appendDocumentation(enum.summary)
        append("export enum ${enum.name} {\n")
        enum.members.forEach { append("    ${lowerCamel(it.name)} = ${it.value},\n") }
        append("}\n")
        if (enum.isFlags) append("\nexport const all${enum.name} = ${enum.allFlagsExpression};\n")
    }

    private fun queryValidationTargets(parameters: List<ParameterDescriptor>): List<ValidationTarget> = parameters.map { parameter ->
        ValidationTarget(lowerCamel(parameter.name), parameter.validationRules.clientRepresentable(), parameter.hasDefault, shared.reaches(parameter.shape))
    }.filter(ValidationTarget::hasRules)

    private fun renderQuery(query: QueryDescriptor, target: ArtifactTarget, route: String): String {
        val parameters = query.parameters.filter { parameter -> parameter.source == QueryParameterSource.CLIENT }
            .sortedBy(ParameterDescriptor::name)
        val validationTargets = queryValidationTargets(parameters)
        val model = resolver.resolveType(query.returnTypeName, target)
        val parameterTypes = parameters.map { resolveParameterType(it, target) }
        val referencedTypes = listOf(model) + parameterTypes
        val imports = customImports(referencedTypes, referencedTypes)
        val sortableProperties = returnedSortProperties(query)
        val className = target.typeScriptName
        val method = query.queryHttpMethod.takeIf {
            options.endpointOptions.enableQueryHttpMethod && it.name != "AUTO"
        }?.let { upperCamel(it.name.lowercase()) }
        val supportsSorting = query.isEnumerable && query.supportsSorting
        val renderModel = QueryRenderModel(
            query = query,
            target = target,
            route = route,
            parameters = parameters,
            validationTargets = validationTargets,
            model = model,
            referencedTypes = referencedTypes,
            imports = imports,
            sortableProperties = sortableProperties,
            className = className,
            modelType = model.name + if (query.isEnumerable) "[]" else "",
            parameterType = if (parameters.isEmpty()) "" else ", ${className}Parameters",
            method = method,
            supportsSorting = supportsSorting,
            supportsPaging = query.isEnumerable && query.supportsPaging,
            hasSortableProperties = supportsSorting && sortableProperties.isNotEmpty()
        )
        return renderQueryFile(renderModel, observable = query.transport == QueryTransportType.OBSERVABLE)
    }

    private fun renderQueryFile(m: QueryRenderModel, observable: Boolean): String = buildString {
        appendGeneratedHeader()
        append("/* eslint-disable sort-imports */\n")
        append("// eslint-disable-next-line header/header\n")
        val flags = QueryImportFlags(
            isEnumerable = m.query.isEnumerable,
            hasValidation = m.validationTargets.isNotEmpty(),
            hasHttpMethod = m.method != null,
            supportsSorting = m.supportsSorting,
            supportsPaging = m.supportsPaging,
            hasSortableProperties = m.hasSortableProperties
        )
        if (observable) appendObservableQueryImports(flags) else appendQueryImports(flags)
        append("import { ParameterDescriptor } from '@cratis/arc/reflection';\n")
        appendPackageImports(m.referencedTypes)
        appendCustomImports(m.imports, m.target)
        if (m.supportsSorting) {
            if (observable) {
                appendObservableSortHelpers(m.className, m.model.name, m.sortableProperties)
            } else {
                appendSortHelpers(m.className, m.model.name, m.sortableProperties)
            }
        }
        appendQueryParametersInterface(m, observable)
        if (m.validationTargets.isNotEmpty()) append("\n")
        validators.appendQueryValidator(this, m.query, m.target, m.validationTargets)
        val bare = m.parameters.isEmpty() && m.validationTargets.isEmpty()
        append(
            when {
                m.query.isEnumerable && bare -> "\n"
                !m.query.isEnumerable && bare -> "\n\n\n"
                else -> "\n\n"
            }
        )
        appendDocumentation(m.query.summary)
        val baseClass = if (observable) "ObservableQueryFor" else "QueryFor"
        append("export class ${m.className} extends $baseClass<${m.modelType}${m.parameterType}> {\n")
        appendQueryClassFields(m)
        appendQueryParameterMembers(m)
        if (m.supportsSorting) {
            append("\n    get sortBy(): ${m.className}SortBy {\n        return this._sortBy;\n    }\n")
            append("\n    static get sortBy(): ${m.className}SortByWithoutQuery {\n        return this._sortBy;\n    }\n")
        }
        if (observable) {
            appendObservableQueryHooks(m.query, m.className, m.model.name, m.parameters.isNotEmpty())
        } else {
            appendQueryHooks(m.query, m.className, m.model.name, m.parameters.isNotEmpty())
        }
        append("}\n")
    }

    private fun StringBuilder.appendQueryParametersInterface(m: QueryRenderModel, observable: Boolean) {
        if (m.parameters.isEmpty()) return
        append(if (m.query.isEnumerable) "\n" else "\n\n")
        appendDocumentation(m.query.summary)
        append("export interface ${m.className}Parameters {\n")
        m.parameters.forEach { parameter ->
            if (observable) append("    \n")
            val optional = if (parameter.isNullable || parameter.hasDefault) "?" else ""
            val array = parameterArraySuffix(parameter)
            appendDocumentation(parameter.summary, "    ")
            append("    ${lowerCamel(parameter.name)}$optional: ${resolveParameterType(parameter, m.target).name}$array;\n")
        }
        append("}\n")
    }

    private fun StringBuilder.appendQueryClassFields(m: QueryRenderModel) {
        append("    readonly route: string = '${escape(m.route)}';\n")
        append("    readonly queryName: string = '${escape(m.query.fullyQualifiedName)}';\n")
        if (m.validationTargets.isNotEmpty()) {
            append("    readonly validation: QueryValidator = new ${m.className}Validator();\n")
        }
        append("    readonly treatWarningsAsErrors: boolean = ${m.query.treatWarningsAsErrors};\n")
        append("    readonly roles: string[] = ${roles(m.query.authorization.roles)};\n")
        append("    readonly defaultValue: ${m.modelType} = ${if (m.query.isEnumerable) "[]" else "{} as any"};\n")
        if (m.supportsSorting) {
            append("    private readonly _sortBy: ${m.className}SortBy;\n")
            append("    private static readonly _sortBy: ${m.className}SortByWithoutQuery = new ${m.className}SortByWithoutQuery();\n")
        }
        append("\n    constructor() {\n        super(${m.model.constructor}, ${m.query.isEnumerable});\n")
        if (m.supportsSorting) append("        this._sortBy = new ${m.className}SortBy(this);\n")
        if (m.method != null) append("        this.setHttpMethod(QueryHttpMethod.${m.method});\n")
        append("    }\n\n")
    }

    private fun StringBuilder.appendQueryParameterMembers(m: QueryRenderModel) {
        append("    get requiredRequestParameters(): string[] {\n        return [\n")
        m.parameters.filter { parameter -> !parameter.isNullable && !parameter.hasDefault }.forEach {
            append("            '${lowerCamel(it.name)}',\n")
        }
        append("        ];\n    }\n\n")
        append("    readonly parameterDescriptors: ParameterDescriptor[] = [\n")
        m.parameters.forEach { parameter ->
            append("        new ParameterDescriptor('${lowerCamel(parameter.name)}', ${resolveParameterType(parameter, m.target).constructor}, ${parameter.isEnumerable}),\n")
        }
        append("    ];\n\n")
        m.parameters.forEach { parameter ->
            val optional = if (parameter.hasDefault) "?" else "!"
            val array = parameterArraySuffix(parameter)
            append("    ${lowerCamel(parameter.name)}$optional: ${resolveParameterType(parameter, m.target).name}$array;\n")
        }
        if (!m.query.isEnumerable) append("\n")
    }

    private fun StringBuilder.appendSharedImports(target: ArtifactTarget, shapes: List<TypeShapeDescriptor>) {
        append("import { Validator as ArcSharedValidator, ValidationResult as ArcSharedResult, ValidationResultSeverity as ArcSharedSeverity } from '@cratis/arc/validation';\n")
        shapes.flatMap(shared::leaves).filter { shared.contains(it) && it != target.sourceName }.distinct().sorted().forEach { name ->
            val nested = targetBySource[name] ?: throw GradleException("[ARCVALIDATION_GRAPH] Missing shared model '$name'.")
            append("import { ${nested.typeScriptName}Validator as ${sharedAlias(name)} } from '${relativeImport(target.directory, nested)}';\n")
        }
    }

    private fun sameRule(left: ValidationRuleDescriptor, right: ValidationRuleDescriptor): Boolean =
        left.ruleName == right.ruleName && left.message == right.message && left.arguments.size == right.arguments.size &&
            left.arguments.zip(right.arguments).all { (a, b) ->
                if (a is Number && b is Number) a.toString().toBigDecimal().compareTo(b.toString().toBigDecimal()) == 0 else a == b
            }

    /** Select only declared return-row fields; request arguments are not sortable row members. */
    private fun returnedSortProperties(query: QueryDescriptor): List<String> {
        if (!query.isEnumerable || !query.supportsSorting) return emptyList()
        val names = sortedSetOf<String>()
        val visited = mutableSetOf<String>()
        var typeName: String? = query.returnTypeName
        while (typeName != null) {
            if (!visited.add(typeName)) {
                throw GradleException("Cyclic Arc return-model inheritance for '${query.fullyQualifiedName}' at '$typeName'.")
            }
            val model = artifacts.types.firstOrNull { it.fullyQualifiedName == typeName }
            if (model == null) {
                // Interface inheritance is not represented by the manifest; do not invent missing members.
                artifacts.interfaces.firstOrNull { it.fullyQualifiedName == typeName }?.properties?.forEach {
                    names += lowerCamel(it.name)
                }
                break
            }
            model.properties.forEach { names += lowerCamel(it.name) }
            typeName = model.baseTypeName
        }
        names.firstOrNull { it == "constructor" || !TYPESCRIPT_IDENTIFIER.matches(it) }?.let { name ->
            throw GradleException("Cannot generate Arc sorting helper '${query.fullyQualifiedName}.$name'; rename that return-model member or expose a different result contract.")
        }
        return names.toList()
    }

    private fun routeParameters(route: String, properties: List<PropertyDescriptor>): List<String> {
        val propertyNames = properties.map { lowerCamel(it.name) }.toSet()
        return ROUTE_PARAMETER.findAll(route)
            .map { it.groupValues[1] }
            .filter { it in propertyNames }
            .distinct()
            .toList()
    }

    private fun StringBuilder.appendPackageImports(
        types: Collection<TypeScriptType>,
        additionalImports: Collection<TypeScriptValueImport> = emptyList()
    ) {
        val imports = (types.flatMap { type -> type.recursiveTypes() }.mapNotNull(TypeScriptType::valueImport) +
            additionalImports).distinct()
        val importsByPackage = imports.groupBy(TypeScriptValueImport::packageName)
        importsByPackage.keys.sortedWith(
            compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it }
        ).forEach { packageName ->
            val names = importsByPackage.getValue(packageName).sortedWith(
                compareBy<TypeScriptValueImport> { valueImport ->
                    if (valueImport.packageName == FUNDAMENTALS_PACKAGE) {
                        FUNDAMENTALS_IMPORT_ORDER[valueImport.name] ?: FUNDAMENTALS_IMPORT_ORDER.size
                    } else {
                        0
                    }
                }.thenBy(String.CASE_INSENSITIVE_ORDER, TypeScriptValueImport::name)
                    .thenBy(TypeScriptValueImport::name)
            ).groupBy(TypeScriptValueImport::name).map { (name, references) ->
                if (references.all(TypeScriptValueImport::typeOnly)) "type $name" else name
            }
            append("import { ${names.joinToString(", ")} } from '$packageName';\n")
        }
    }

    private fun customImports(
        types: Collection<TypeScriptType>,
        runtimeTypes: Collection<TypeScriptType> = emptyList()
    ): List<TypeScriptImport> {
        val valueTargets = runtimeTypes.mapNotNull { type ->
            type.target?.takeIf { target -> type.constructor == target.typeScriptName }
        }.toSet()
        return types.flatMap { type -> type.recursiveTypes() }.mapNotNull(TypeScriptType::target).distinct().map { target ->
            TypeScriptImport(
                target,
                if (target in valueTargets) TypeScriptImportKind.VALUE else TypeScriptImportKind.TYPE
            )
        }
    }

    private fun StringBuilder.appendCustomImports(imports: Collection<TypeScriptImport>, current: ArtifactTarget) {
        if (current.kind == ArtifactKind.QUERY) {
            val shapes = artifacts.queries.single { it.fullyQualifiedName == current.sourceName }.parameters
                .filter { it.source == QueryParameterSource.CLIENT }.map { it.shape }
            if (shapes.any(shared::reaches)) appendSharedImports(current, shapes)
        }
        imports.filterNot { it.target.sourceName == current.sourceName }.sortedWith(
            compareBy<TypeScriptImport, String>(String.CASE_INSENSITIVE_ORDER) { import ->
                relativeImport(current.directory, import.target).substringAfterLast('/')
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { import -> import.target.typeScriptName }
        ).forEach { import ->
            val typeOnly = if (import.kind == TypeScriptImportKind.TYPE) " type" else ""
            append(
                "import$typeOnly { ${import.target.typeScriptName} } from " +
                    "'${relativeImport(current.directory, import.target)}';\n"
            )
        }
    }

    private fun relativeImport(from: List<String>, target: ArtifactTarget): String {
        val common = from.zip(target.directory).takeWhile { (source, destination) -> source == destination }.size
        val upward = "../".repeat(from.size - common)
        val downward = (target.directory.drop(common) + target.fileStem).joinToString("/")
        return if (upward.isEmpty()) "./$downward" else "$upward$downward"
    }

    private fun resolvePropertyType(property: PropertyDescriptor, current: ArtifactTarget): TypeScriptType =
        if (property.shape.kind == TypeShapeKind.MAP) {
            resolver.resolveMapShape(property.shape, property.name)
        } else {
            resolver.resolveType(if (property.isEnumerable) requireElementType(property.name, property.elementTypeName) else property.typeName, current)
        }

    private fun propertyTypeName(property: PropertyDescriptor, current: ArtifactTarget): String =
        resolvePropertyType(property, current).name + (if (property.isEnumerable) "[]" else "") +
            if (shared.contains(current.sourceName) && property.isNullable) " | null" else ""

    private fun TypeScriptType.recursiveTypes(): List<TypeScriptType> = listOf(this) + nestedTypes.flatMap {
        nested -> nested.recursiveTypes()
    }

    private fun parameterArraySuffix(parameter: ParameterDescriptor): String =
        if (!parameter.isEnumerable) "" else "[]" + if (parameter.isNullable && shared.reaches(parameter.shape)) " | null" else ""

    private fun resolveParameterType(parameter: ParameterDescriptor, current: ArtifactTarget): TypeScriptType {
        val type = resolver.resolveType(if (parameter.isEnumerable) requireElementType(parameter.name, parameter.elementTypeName) else parameter.typeName, current)
        return if (parameter.isNullable && shared.reaches(parameter.shape) && !parameter.isEnumerable) type.copy(name = type.name + " | null") else type
    }

    private fun resolveDerivatives(property: PropertyDescriptor, current: ArtifactTarget): List<TypeScriptType> =
        property.derivatives.map { derivativeName ->
            val derivative = resolver.resolveType(derivativeName, current)
            if (derivative.target?.kind != ArtifactKind.TYPE) {
                throw GradleException(
                    "Arc derivative '$derivativeName' on '${current.sourceName}.${property.name}' must be a generated concrete model type."
                )
            }
            derivative
        }

    private fun resolveBaseType(baseTypeName: String, current: ArtifactTarget): TypeScriptType {
        val baseType = resolver.resolveType(baseTypeName, current)
        if (baseType.target?.kind != ArtifactKind.TYPE) {
            throw GradleException("Arc base type '$baseTypeName' on '${current.sourceName}' must be a generated model type.")
        }
        return baseType
    }

    private fun requireElementType(name: String, elementTypeName: String?): String =
        elementTypeName ?: throw GradleException("Enumerable '$name' does not declare an elementTypeName.")

    private fun TypeScriptType.globalRuntimeConstructor(): String? {
        if (constructor !in GLOBAL_RUNTIME_CONSTRUCTORS || valueImport?.typeOnly == false) return null
        return constructor.takeIf {
            target == null || target.kind == ArtifactKind.INTERFACE || target.kind == ArtifactKind.ENUM
        }
    }

    /**
     * Emits one source documentation summary as a single-line JSDoc comment.
     *
     * The summary is guaranteed by [io.cratis.arc.metadata.DocumentationSummaries] to be one line, so the comment
     * cannot span lines; [escapeDocumentation] neutralizes the two sequences a JSDoc reader would otherwise act on.
     */
    private fun StringBuilder.appendDocumentation(summary: String?, indent: String = "") {
        if (summary == null) return
        append(indent)
        append("/** ")
        append(escapeDocumentation(summary))
        append(" */\n")
    }

    private fun escapeDocumentation(summary: String): String = buildString(summary.length + 8) {
        summary.forEachIndexed { index, character ->
            when {
                // A comment terminator inside the text would end the comment and spill code into the file.
                character == '/' && index > 0 && summary[index - 1] == '*' -> append(" /")
                // An at sign in tag position would be read as a JSDoc block tag rather than as prose.
                character == '@' && (index == 0 || summary[index - 1].isWhitespace()) -> append("\\@")
                else -> append(character)
            }
        }
    }

    private fun StringBuilder.appendGeneratedHeader() {
        append("/*---------------------------------------------------------------------------------------------\n")
        append(" *  **DO NOT EDIT** - This file is an automatically generated file.\n")
        append(" *--------------------------------------------------------------------------------------------*/\n\n")
    }

    private fun validateTarget(target: ArtifactTarget) {
        target.directory.forEach { segment ->
            if (!SAFE_PATH_SEGMENT.matches(segment) || segment == "." || segment == "..") {
                throw GradleException("Unsafe Arc proxy path segment '$segment' from '${target.sourceName}'.")
            }
        }
        if (!TYPESCRIPT_IDENTIFIER.matches(target.typeScriptName)) {
            throw GradleException(
                "Unsafe Arc proxy TypeScript name '${target.typeScriptName}' from '${target.sourceName}'."
            )
        }
        val root = options.outputDirectory.canonicalFile.toPath()
        val destination = File(options.outputDirectory, target.relativePath).canonicalFile.toPath()
        if (!destination.startsWith(root)) {
            throw GradleException("Arc proxy output '${target.relativePath}' escapes '${options.outputDirectory.path}'.")
        }
    }

}
