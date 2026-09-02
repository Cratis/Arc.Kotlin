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
    val segmentsToSkip: Int
)

private enum class ArtifactKind { COMMAND, TYPE, INTERFACE, ENUM, QUERY }

private data class ArtifactTarget(
    val sourceName: String,
    val typeScriptName: String,
    val directory: List<String>,
    val kind: ArtifactKind
) {
    val relativePath: String = (directory + "$typeScriptName.ts").joinToString("/")
}

private data class TypeScriptValueImport(val packageName: String, val name: String)

private enum class TypeScriptImportKind { TYPE, VALUE }

private data class TypeScriptImport(val target: ArtifactTarget, val kind: TypeScriptImportKind)

private data class TypeScriptType(
    val name: String,
    val constructor: String,
    val target: ArtifactTarget? = null,
    val valueImport: TypeScriptValueImport? = null,
    val nestedTypes: List<TypeScriptType> = emptyList()
)

private data class ValidationTarget(
    val propertyName: String,
    val rules: List<ValidationRuleDescriptor>,
    val skipWhenUndefined: Boolean = false
) {
    val hasRules: Boolean = rules.isNotEmpty()
}

internal class TypeScriptProxyGenerator(
    private val artifacts: MergedArcArtifacts,
    private val options: ProxyGenerationOptions
) {
    private val commandTypeNames = artifacts.commands.map(CommandDescriptor::typeName).toSet()
    private val emittedTypes = artifacts.types.filterNot { it.fullyQualifiedName in commandTypeNames }
    private val queryTargets = buildQueryTargets()
    private val conceptsBySource = artifacts.concepts.associateBy { concept -> concept.fullyQualifiedName }
    private val targets = buildTargets()
    private val targetBySource = targets.associateBy(ArtifactTarget::sourceName)

    fun generate(): List<File> {
        require(options.segmentsToSkip >= 0) { "Proxy segmentsToSkip cannot be negative." }
        validateOutputTree()
        validateArtifacts()
        val generated = linkedMapOf<String, Pair<String, String>>()
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
            generated[target.relativePath] = command.typeName to renderCommand(command, target, route)
        }
        artifacts.queries.forEach { query ->
            val target = queryTarget(query)
            val namespace = query.location.drop(options.endpointOptions.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.queryRoute(
                query,
                options.endpointOptions,
                (queryNamespaces[namespace]?.size ?: 0) > 1
            )
            generated[target.relativePath] = query.declaringTypeName to renderQuery(query, target, route)
        }
        emittedTypes.forEach { type ->
            val target = targetBySource.getValue(type.fullyQualifiedName)
            generated[target.relativePath] = type.fullyQualifiedName to renderType(type, target)
        }
        artifacts.interfaces.forEach { interfaceDescriptor ->
            val target = targetBySource.getValue(interfaceDescriptor.fullyQualifiedName)
            generated[target.relativePath] = interfaceDescriptor.fullyQualifiedName to
                renderInterface(interfaceDescriptor, target)
        }
        artifacts.enums.forEach { enum ->
            val target = targetBySource.getValue(enum.fullyQualifiedName)
            generated[target.relativePath] = enum.fullyQualifiedName to renderEnum(enum)
        }

        val expectedFiles = generated.keys.map { File(options.outputDirectory, it).canonicalFile }.toSet()
        val written = generated.toSortedMap().map { (relativePath, sourceAndBody) ->
            val file = File(options.outputDirectory, relativePath)
            writeGenerated(file, sourceAndBody.first, sourceAndBody.second)
            file
        }
        val removed = if (options.removeStaleGeneratedFiles && options.outputDirectory.isDirectory) {
            removeStale(expectedFiles)
        } else {
            emptySet()
        }
        val indexes = updateIndexFiles(generated.keys, removed)
        removeEmptyDirectories()
        return written + indexes
    }

    private fun buildTargets(): List<ArtifactTarget> {
        val result = mutableListOf<ArtifactTarget>()
        artifacts.commands.forEach {
            result += ArtifactTarget(it.typeName, it.name, outputDirectory(it.location), ArtifactKind.COMMAND)
        }
        emittedTypes.forEach {
            result += ArtifactTarget(it.fullyQualifiedName, it.name, outputDirectory(it.location), ArtifactKind.TYPE)
        }
        artifacts.interfaces.forEach {
            result += ArtifactTarget(it.fullyQualifiedName, it.name, outputDirectory(it.location), ArtifactKind.INTERFACE)
        }
        artifacts.enums.forEach {
            result += ArtifactTarget(it.fullyQualifiedName, it.name, outputDirectory(it.location), ArtifactKind.ENUM)
        }
        artifacts.queries.forEach { result += queryTarget(it) }
        return result
    }

    private fun buildQueryTargets(): Map<String, ArtifactTarget> = artifacts.queries.associate { query ->
        query.fullyQualifiedName to ArtifactTarget(
            query.fullyQualifiedName,
            upperCamel(query.name),
            outputDirectory(query.location),
            ArtifactKind.QUERY
        )
    }

    private fun queryTarget(query: QueryDescriptor): ArtifactTarget = queryTargets.getValue(query.fullyQualifiedName)

    private fun outputDirectory(location: List<String>): List<String> = location.drop(options.segmentsToSkip)

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
                listOfNotNull(command.responseTypeName?.let { resolveType(it, current) })
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
            val referencedTypes = listOf(resolveType(query.returnTypeName, target)) +
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
        artifacts.interfaces.forEach { interfaceDescriptor ->
            val current = targetBySource.getValue(interfaceDescriptor.fullyQualifiedName)
            validateImportedTypeNames(
                current,
                interfaceDescriptor.properties.map { resolvePropertyType(it, current) }
            )
        }
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
        val response = command.responseTypeName?.let { resolveType(it, target) }
        val referencedTypes = propertyTypes + listOfNotNull(response)
        val imports = customImports(referencedTypes, referencedTypes)
        val interfaceName = "I${command.name}"
        val validationTargets = command.properties.map { property ->
            ValidationTarget(lowerCamel(property.name), property.validationRules.clientRepresentable())
        }.filter(ValidationTarget::hasRules).sortedBy(ValidationTarget::propertyName)
        val requestParameters = routeParameters(route, command.properties)
        val requiresStringMapGuard = command.properties.any { property -> property.shape.containsMap() }
        return buildString {
            appendGeneratedHeader()
            append("/* eslint-disable sort-imports */\n")
            append("/* eslint-disable @typescript-eslint/no-empty-interface */\n")
            append("// eslint-disable-next-line header/header\n")
            append("import { Command${if (validationTargets.isNotEmpty()) ", CommandValidator" else ""} } from '@cratis/arc/commands';\n")
            append("import { useCommand, type SetCommandValues, type ClearCommandValues } from '@cratis/arc.react/commands';\n")
            append("import { PropertyDescriptor } from '@cratis/arc/reflection';\n")
            appendPackageImports(referencedTypes)
            appendCustomImports(imports, target)
            append("\n")
            if (requiresStringMapGuard) {
                appendStringMapGuard()
            }
            append("export interface $interfaceName {\n")
            command.properties.forEach { property ->
                append("    ${lowerCamel(property.name)}?: ${propertyTypeName(property, target)};\n")
            }
            append("}\n\n")
            appendValidator("${command.name}Validator", "CommandValidator", interfaceName, validationTargets)
            val responseGeneric = response?.let {
                ", ${it.name}${if (command.responseIsEnumerable) "[]" else ""}"
            }.orEmpty()
            append("export class ${command.name} extends Command<$interfaceName$responseGeneric> implements $interfaceName {\n")
            append("    readonly route: string = '${escape(route)}';\n")
            if (validationTargets.isNotEmpty()) {
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
            append("\n")
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
                append("    @field(${fieldArguments.joinToString(", ")})\n")
                val marker = if (property.isNullable) "?" else "!"
                append("    ${lowerCamel(property.name)}$marker: ${propertyTypeName(property, target)};\n")
            }
            append("}\n")
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
            append("\nexport interface ${interfaceDescriptor.name} {\n")
            interfaceDescriptor.properties.forEach { property ->
                val optional = if (property.isNullable) "?" else ""
                append("    ${lowerCamel(property.name)}$optional: ${propertyTypeName(property, target)};\n")
            }
            append("}\n")
        }
    }

    private fun renderEnum(enum: EnumDescriptor): String = buildString {
        appendGeneratedHeader()
        append("// eslint-disable-next-line header/header\n")
        append("export enum ${enum.name} {\n")
        enum.members.forEach { append("    ${lowerCamel(it.name)} = ${it.value},\n") }
        append("}\n")
        if (enum.isFlags) append("\nexport const all${enum.name} = ${enum.allFlagsExpression};\n")
    }

    private fun renderQuery(query: QueryDescriptor, target: ArtifactTarget, route: String): String {
        if (query.transport == QueryTransportType.OBSERVABLE) {
            return renderObservableQuery(query, target, route)
        }
        val parameters = query.parameters.filter { parameter -> parameter.source == QueryParameterSource.CLIENT }
            .sortedBy(ParameterDescriptor::name)
        val validationTargets = parameters.map { parameter ->
            ValidationTarget(
                lowerCamel(parameter.name),
                parameter.validationRules.clientRepresentable(),
                parameter.hasDefault
            )
        }.filter(ValidationTarget::hasRules)
        val model = resolveType(query.returnTypeName, target)
        val parameterTypes = parameters.map { resolveParameterType(it, target) }
        val referencedTypes = listOf(model) + parameterTypes
        val imports = customImports(referencedTypes, referencedTypes)
        val sortableProperties = parameters.map(ParameterDescriptor::name)
        val className = target.typeScriptName
        val modelType = model.name + if (query.isEnumerable) "[]" else ""
        val parameterType = if (parameters.isEmpty()) "" else ", ${className}Parameters"
        val method = query.queryHttpMethod.takeIf {
            options.endpointOptions.enableQueryHttpMethod && it.name != "AUTO"
        }?.let { upperCamel(it.name.lowercase()) }
        val supportsSorting = query.isEnumerable && query.supportsSorting
        val supportsPaging = query.isEnumerable && query.supportsPaging
        val hasSortableProperties = supportsSorting && sortableProperties.isNotEmpty()
        return buildString {
            appendGeneratedHeader()
            append("/* eslint-disable sort-imports */\n")
            append("// eslint-disable-next-line header/header\n")
            val validationImport = if (validationTargets.isNotEmpty()) ", QueryValidator" else ""
            if (query.isEnumerable) {
                val sorting = if (supportsSorting) ", Sorting" else ""
                val sortingActions = if (hasSortableProperties) ", SortingActions, SortingActionsForQuery" else ""
                val paging = if (supportsPaging) ", Paging" else ""
                val httpMethod = if (method != null) ", QueryHttpMethod" else ""
                append("import { QueryFor, QueryResultWithState$validationImport$sorting$sortingActions$paging$httpMethod } from '@cratis/arc/queries';\n")
                val hooks = if (supportsPaging) {
                    "useQuery, useQueryWithPaging, useSuspenseQuery, useSuspenseQueryWithPaging"
                } else {
                    "useQuery, useSuspenseQuery"
                }
                val sortingType = if (supportsSorting) ", type SetSorting" else ""
                val pagingTypes = if (supportsPaging) ", type SetPage, type SetPageSize" else ""
                append("import { $hooks, type PerformQuery$sortingType$pagingTypes, QueryWhen } from '@cratis/arc.react/queries';\n")
            } else {
                val httpMethod = if (method != null) ", QueryHttpMethod" else ""
                append("import { QueryFor, QueryResultWithState$validationImport$httpMethod } from '@cratis/arc/queries';\n")
                append("import { useQuery, useSuspenseQuery, type PerformQuery, type SetSorting, QueryWhen } from '@cratis/arc.react/queries';\n")
            }
            append("import { ParameterDescriptor } from '@cratis/arc/reflection';\n")
            appendPackageImports(referencedTypes)
            appendCustomImports(imports, target)
            if (supportsSorting) appendSortHelpers(className, model.name, sortableProperties)
            if (parameters.isNotEmpty()) {
                val parameterSpacing = if (query.isEnumerable) "\n" else "\n\n"
                append("${parameterSpacing}export interface ${className}Parameters {\n")
                parameters.forEach { parameter ->
                    val optional = if (parameter.isNullable || parameter.hasDefault) "?" else ""
                    val array = if (parameter.isEnumerable) "[]" else ""
                    append("    ${lowerCamel(parameter.name)}$optional: ${resolveParameterType(parameter, target).name}$array;\n")
                }
                append("}\n")
            }
            if (validationTargets.isNotEmpty()) append("\n")
            appendValidator("${className}Validator", "QueryValidator", "${className}Parameters", validationTargets)
            val classSpacing = when {
                query.isEnumerable && parameters.isEmpty() && validationTargets.isEmpty() -> "\n"
                !query.isEnumerable && parameters.isEmpty() && validationTargets.isEmpty() -> "\n\n\n"
                else -> "\n\n"
            }
            append("${classSpacing}export class $className extends QueryFor<$modelType$parameterType> {\n")
            append("    readonly route: string = '${escape(route)}';\n")
            append("    readonly queryName: string = '${escape(query.fullyQualifiedName)}';\n")
            if (validationTargets.isNotEmpty()) {
                append("    readonly validation: QueryValidator = new ${className}Validator();\n")
            }
            append("    readonly treatWarningsAsErrors: boolean = ${query.treatWarningsAsErrors};\n")
            append("    readonly roles: string[] = ${roles(query.authorization.roles)};\n")
            append("    readonly defaultValue: $modelType = ${if (query.isEnumerable) "[]" else "{} as any"};\n")
            if (supportsSorting) {
                append("    private readonly _sortBy: ${className}SortBy;\n")
                append("    private static readonly _sortBy: ${className}SortByWithoutQuery = new ${className}SortByWithoutQuery();\n")
            }
            append("\n    constructor() {\n        super(${model.constructor}, ${query.isEnumerable});\n")
            if (supportsSorting) append("        this._sortBy = new ${className}SortBy(this);\n")
            if (method != null) append("        this.setHttpMethod(QueryHttpMethod.$method);\n")
            append("    }\n\n")
            append("    get requiredRequestParameters(): string[] {\n        return [\n")
            parameters.filter { parameter -> !parameter.isNullable && !parameter.hasDefault }.forEach {
                append("            '${lowerCamel(it.name)}',\n")
            }
            append("        ];\n    }\n\n")
            append("    readonly parameterDescriptors: ParameterDescriptor[] = [\n")
            parameters.forEach { parameter ->
                append("        new ParameterDescriptor('${lowerCamel(parameter.name)}', ${resolveParameterType(parameter, target).constructor}, ${parameter.isEnumerable}),\n")
            }
            append("    ];\n\n")
            parameters.forEach { parameter ->
                val optional = if (parameter.hasDefault) "?" else "!"
                val array = if (parameter.isEnumerable) "[]" else ""
                append("    ${lowerCamel(parameter.name)}$optional: ${resolveParameterType(parameter, target).name}$array;\n")
            }
            if (!query.isEnumerable) append("\n")
            if (supportsSorting) {
                append("\n    get sortBy(): ${className}SortBy {\n        return this._sortBy;\n    }\n")
                append("\n    static get sortBy(): ${className}SortByWithoutQuery {\n        return this._sortBy;\n    }\n")
            }
            appendQueryHooks(query, className, model.name, parameters.isNotEmpty())
            append("}\n")
        }
    }

    private fun renderObservableQuery(query: QueryDescriptor, target: ArtifactTarget, route: String): String {
        val parameters = query.parameters.filter { parameter -> parameter.source == QueryParameterSource.CLIENT }
            .sortedBy(ParameterDescriptor::name)
        val validationTargets = parameters.map { parameter ->
            ValidationTarget(
                lowerCamel(parameter.name),
                parameter.validationRules.clientRepresentable(),
                parameter.hasDefault
            )
        }.filter(ValidationTarget::hasRules)
        val model = resolveType(query.returnTypeName, target)
        val parameterTypes = parameters.map { resolveParameterType(it, target) }
        val referencedTypes = listOf(model) + parameterTypes
        val imports = customImports(referencedTypes, referencedTypes)
        val sortableProperties = parameters.map(ParameterDescriptor::name)
        val className = target.typeScriptName
        val modelType = model.name + if (query.isEnumerable) "[]" else ""
        val parameterType = if (parameters.isEmpty()) "" else ", ${className}Parameters"
        val method = query.queryHttpMethod.takeIf {
            options.endpointOptions.enableQueryHttpMethod && it.name != "AUTO"
        }?.let { upperCamel(it.name.lowercase()) }
        val supportsSorting = query.isEnumerable && query.supportsSorting
        val supportsPaging = query.isEnumerable && query.supportsPaging
        val hasSortableProperties = supportsSorting && sortableProperties.isNotEmpty()
        return buildString {
            appendGeneratedHeader()
            append("/* eslint-disable sort-imports */\n")
            append("// eslint-disable-next-line header/header\n")
            val validationImport = if (validationTargets.isNotEmpty()) ", QueryValidator" else ""
            if (query.isEnumerable) {
                val sorting = if (supportsSorting) ", Sorting" else ""
                val sortingActions = if (hasSortableProperties) {
                    ", SortingActions, SortingActionsForObservableQuery"
                } else {
                    ""
                }
                val paging = if (supportsPaging) ", Paging" else ""
                val httpMethod = if (method != null) ", QueryHttpMethod" else ""
                append("import { ObservableQueryFor, QueryResultWithState$validationImport$sorting$sortingActions$paging, type ChangeSet$httpMethod } from '@cratis/arc/queries';\n")
                val hooks = if (supportsPaging) {
                    "useObservableQuery, useObservableQueryWithPaging, useSuspenseObservableQuery, " +
                        "useSuspenseObservableQueryWithPaging"
                } else {
                    "useObservableQuery, useSuspenseObservableQuery"
                }
                val sortingType = if (supportsSorting) ", type SetSorting" else ""
                val pagingTypes = if (supportsPaging) ", type SetPage, type SetPageSize" else ""
                append("import { $hooks, useChangeStream$sortingType$pagingTypes, ObservableQueryWhen } from '@cratis/arc.react/queries';\n")
            } else {
                val httpMethod = if (method != null) ", QueryHttpMethod" else ""
                append("import { ObservableQueryFor, QueryResultWithState$validationImport$httpMethod } from '@cratis/arc/queries';\n")
                append("import { useObservableQuery, useSuspenseObservableQuery, ObservableQueryWhen } from '@cratis/arc.react/queries';\n")
            }
            append("import { ParameterDescriptor } from '@cratis/arc/reflection';\n")
            appendPackageImports(referencedTypes)
            appendCustomImports(imports, target)
            if (supportsSorting) appendObservableSortHelpers(className, model.name, sortableProperties)
            if (parameters.isNotEmpty()) {
                val parameterSpacing = if (query.isEnumerable) "\n" else "\n\n"
                append("${parameterSpacing}export interface ${className}Parameters {\n")
                parameters.forEach { parameter ->
                    append("    \n")
                    val optional = if (parameter.isNullable || parameter.hasDefault) "?" else ""
                    val array = if (parameter.isEnumerable) "[]" else ""
                    append("    ${lowerCamel(parameter.name)}$optional: ${resolveParameterType(parameter, target).name}$array;\n")
                }
                append("}\n")
            }
            if (validationTargets.isNotEmpty()) append("\n")
            appendValidator("${className}Validator", "QueryValidator", "${className}Parameters", validationTargets)
            val classSpacing = when {
                query.isEnumerable && parameters.isEmpty() && validationTargets.isEmpty() -> "\n"
                !query.isEnumerable && parameters.isEmpty() && validationTargets.isEmpty() -> "\n\n\n"
                else -> "\n\n"
            }
            append("${classSpacing}export class $className extends ObservableQueryFor<$modelType$parameterType> {\n")
            append("    readonly route: string = '${escape(route)}';\n")
            append("    readonly queryName: string = '${escape(query.fullyQualifiedName)}';\n")
            if (validationTargets.isNotEmpty()) {
                append("    readonly validation: QueryValidator = new ${className}Validator();\n")
            }
            append("    readonly treatWarningsAsErrors: boolean = ${query.treatWarningsAsErrors};\n")
            append("    readonly roles: string[] = ${roles(query.authorization.roles)};\n")
            append("    readonly defaultValue: $modelType = ${if (query.isEnumerable) "[]" else "{} as any"};\n")
            if (supportsSorting) {
                append("    private readonly _sortBy: ${className}SortBy;\n")
                append("    private static readonly _sortBy: ${className}SortByWithoutQuery = new ${className}SortByWithoutQuery();\n")
            }
            append("\n    constructor() {\n        super(${model.constructor}, ${query.isEnumerable});\n")
            if (supportsSorting) append("        this._sortBy = new ${className}SortBy(this);\n")
            if (method != null) append("        this.setHttpMethod(QueryHttpMethod.$method);\n")
            append("    }\n\n")
            append("    get requiredRequestParameters(): string[] {\n        return [\n")
            parameters.filter { parameter -> !parameter.isNullable && !parameter.hasDefault }.forEach {
                append("            '${lowerCamel(it.name)}',\n")
            }
            append("        ];\n    }\n\n")
            append("    readonly parameterDescriptors: ParameterDescriptor[] = [\n")
            parameters.forEach { parameter ->
                append("        new ParameterDescriptor('${lowerCamel(parameter.name)}', ${resolveParameterType(parameter, target).constructor}, ${parameter.isEnumerable}),\n")
            }
            append("    ];\n\n")
            parameters.forEach { parameter ->
                val optional = if (parameter.hasDefault) "?" else "!"
                val array = if (parameter.isEnumerable) "[]" else ""
                append("    ${lowerCamel(parameter.name)}$optional: ${resolveParameterType(parameter, target).name}$array;\n")
            }
            if (!query.isEnumerable) append("\n")
            if (supportsSorting) {
                append("\n    get sortBy(): ${className}SortBy {\n        return this._sortBy;\n    }\n")
                append("\n    static get sortBy(): ${className}SortByWithoutQuery {\n        return this._sortBy;\n    }\n")
            }
            appendObservableQueryHooks(query, className, model.name, parameters.isNotEmpty())
            append("}\n")
        }
    }

    private fun StringBuilder.appendValidator(
        className: String,
        validatorType: String,
        targetType: String,
        targets: List<ValidationTarget>
    ) {
        if (targets.isEmpty()) return
        append("export class $className extends $validatorType<$targetType> {\n")
        append("    constructor() {\n")
        append("        super();\n")
        targets.forEach { target ->
            target.rules.forEach { rule ->
                append("        this.ruleFor(c => c.${target.propertyName}).${rule.ruleName}(")
                append(renderValidationArguments(rule))
                append(")")
                rule.message?.let { message -> append(".withMessage(${typescriptString(message)})") }
                append(";\n")
            }
        }
        append("    }\n")
        val optionalTargets = targets.filter(ValidationTarget::skipWhenUndefined)
        if (optionalTargets.isNotEmpty()) {
            append("\n    validate(query: $targetType) {\n")
            append("        return super.validate(query).filter(result => !(\n")
            optionalTargets.forEachIndexed { index, target ->
                val prefix = if (index == 0) "            " else "            || "
                append("$prefix(query.${target.propertyName} === undefined && result.members.some(member => ")
                append("member === '${target.propertyName}' || member.startsWith('${target.propertyName}.')))\n")
            }
            append("        ));\n")
            append("    }\n")
        }
        append("}\n\n")
    }

    private fun List<ValidationRuleDescriptor>.clientRepresentable(): List<ValidationRuleDescriptor> =
        filterNot { rule ->
            // .NET currently extracts CreditCardAttribute, but @cratis/arc 22.7.0 exposes no creditCard RuleBuilder
            // extension or runtime rule. Keep server enforcement and metadata without generating uncompilable proxies.
            rule.ruleName == "creditCard"
        }

    private fun renderValidationArguments(rule: ValidationRuleDescriptor): String =
        rule.arguments.mapIndexed { index, argument ->
            if (rule.ruleName == "matches" && index == 0) {
                regularExpression(argument as? String ?: throw GradleException("Regular expression argument must be a string."))
            } else {
                when (argument) {
                    is String -> typescriptString(argument)
                    is Number, is Boolean -> argument.toString()
                    else -> throw GradleException(
                        "Unsupported argument '$argument' for Arc validation rule '${rule.ruleName}'."
                    )
                }
            }
        }.joinToString(", ")

    private fun regularExpression(pattern: String): String {
        if (pattern.isEmpty()) return "/(?:)/"
        val literal = StringBuilder(pattern.length + 2)
        val escaped = pattern.fold(false) { previousWasEscape, character ->
            if (character == '\\' && !previousWasEscape) {
                literal.append('\\')
                true
            } else {
                when (character) {
                    '/' -> literal.append(if (previousWasEscape) "/" else "\\/")
                    '\r' -> literal.append(if (previousWasEscape) "r" else "\\r")
                    '\n' -> literal.append(if (previousWasEscape) "n" else "\\n")
                    '\u2028' -> literal.append(if (previousWasEscape) "u2028" else "\\u2028")
                    '\u2029' -> literal.append(if (previousWasEscape) "u2029" else "\\u2029")
                    else -> literal.append(character)
                }
                false
            }
        }
        if (escaped) literal.append('\\')
        return "/$literal/"
    }

    private fun typescriptString(value: String): String = "'" + value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "'"

    private fun StringBuilder.appendObservableSortHelpers(
        className: String,
        model: String,
        properties: List<String>
    ) {
        append("\nclass ${className}SortBy {\n")
        properties.forEach { property ->
            append("    private _${lowerCamel(property)}: SortingActionsForObservableQuery<$model[]>;\n")
        }
        append("\n    constructor(readonly query: $className) {\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("        this._$name = new SortingActionsForObservableQuery<$model[]>('$name', query);\n")
        }
        append("    }\n")
        if (properties.isNotEmpty()) append("\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("    get $name(): SortingActionsForObservableQuery<$model[]> {\n        return this._$name;\n    }\n")
        }
        if (properties.isEmpty()) append("\n")
        append("}\n\nclass ${className}SortByWithoutQuery {\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("    private _$name: SortingActions  = new SortingActions('$name');\n")
        }
        append("\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("    get $name(): SortingActions {\n        return this._$name;\n    }\n")
        }
        append("}\n")
        if (properties.isEmpty()) append("\n")
    }

    private fun StringBuilder.appendObservableQueryHooks(
        query: QueryDescriptor,
        className: String,
        model: String,
        hasParameters: Boolean
    ) {
        val argsType = if (hasParameters) "${className}Parameters" else null
        val argsDeclaration = argsType?.let { "args?: $it" }
        val argsValue = if (hasParameters) "args" else "undefined"
        val genericParameter = argsType?.let { ", $it" }.orEmpty()
        if (query.isEnumerable) {
            val sortingArgument = "sorting?: Sorting".takeIf { query.supportsSorting }
            val firstArguments = listOfNotNull(argsDeclaration, sortingArgument).joinToString(", ")
            val useTypes = listOf("QueryResultWithState<$model[]>") +
                listOfNotNull("SetSorting".takeIf { query.supportsSorting })
            append("\n    static use($firstArguments): [${useTypes.joinToString(", ")}] {\n")
            if (query.supportsSorting) {
                append("        return useObservableQuery<$model[], $className$genericParameter>($className, $argsValue, sorting);\n    }\n")
            } else {
                append("        const [result] = useObservableQuery<$model[], $className$genericParameter>($className, $argsValue);\n")
                append("        return [result];\n    }\n")
            }
            if (query.supportsPaging) {
                val pagingArguments = listOf("pageSize: number") + listOfNotNull(argsDeclaration, sortingArgument)
                val pagingTypes = useTypes + listOf("SetPage", "SetPageSize")
                val sortingValue = if (query.supportsSorting) "sorting" else "undefined"
                append("\n    static useWithPaging(${pagingArguments.joinToString(", ")}): [${pagingTypes.joinToString(", ")}] {\n")
                if (query.supportsSorting) {
                    append("        return useObservableQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n    }\n")
                } else {
                    append("        const [result, , setPage, setPageSize] = useObservableQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n")
                    append("        return [result, setPage, setPageSize];\n    }\n")
                }
            }
            append("\n    static useSuspense($firstArguments): [${useTypes.joinToString(", ")}] {\n")
            if (query.supportsSorting) {
                append("        return useSuspenseObservableQuery<$model[], $className$genericParameter>($className, $argsValue, sorting);\n    }\n")
            } else {
                append("        const [result] = useSuspenseObservableQuery<$model[], $className$genericParameter>($className, $argsValue);\n")
                append("        return [result];\n    }\n")
            }
            if (query.supportsPaging) {
                val pagingArguments = listOf("pageSize: number") + listOfNotNull(argsDeclaration, sortingArgument)
                val pagingTypes = useTypes + listOf("SetPage", "SetPageSize")
                val sortingValue = if (query.supportsSorting) "sorting" else "undefined"
                append("\n    static useSuspenseWithPaging(${pagingArguments.joinToString(", ")}): [${pagingTypes.joinToString(", ")}] {\n")
                if (query.supportsSorting) {
                    append("        return useSuspenseObservableQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n    }\n")
                } else {
                    append("        const [result, , setPage, setPageSize] = useSuspenseObservableQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n")
                    append("        return [result, setPage, setPageSize];\n    }\n")
                }
            }
            val changeStreamArguments = listOfNotNull(
                argsDeclaration,
                "getKey?: (item: $model) => unknown",
                sortingArgument
            ).joinToString(", ")
            val sortingValue = if (query.supportsSorting) "sorting" else "undefined"
            append("\n    static useChangeStream($changeStreamArguments): ChangeSet<$model> {\n")
            append("        return useChangeStream<$model, $className$genericParameter>($className, $argsValue, getKey, $sortingValue);\n    }\n")
            append("\n    static when(condition: boolean): ObservableQueryWhen<$className, $model[]$genericParameter> {\n")
            append("        return new ObservableQueryWhen<$className, $model[]$genericParameter>($className, condition);\n    }\n")
        } else {
            val declaration = argsDeclaration.orEmpty()
            append("\n    static use($declaration): [QueryResultWithState<$model>] {\n")
            append("        const [result] = useObservableQuery<$model, $className$genericParameter>($className${if (hasParameters) ", args" else ""});\n")
            append("        return [result];\n    }\n")
            append("\n    static useSuspense($declaration): [QueryResultWithState<$model>] {\n")
            append("        const [result] = useSuspenseObservableQuery<$model, $className$genericParameter>($className${if (hasParameters) ", args" else ""});\n")
            append("        return [result];\n    }\n")
            append("\n    static when(condition: boolean): ObservableQueryWhen<$className, $model$genericParameter> {\n")
            append("        return new ObservableQueryWhen<$className, $model$genericParameter>($className, condition);\n    }\n")
        }
    }

    private fun StringBuilder.appendSortHelpers(className: String, model: String, properties: List<String>) {
        append("\nclass ${className}SortBy {\n")
        properties.forEach { property ->
            append("    private _${lowerCamel(property)}: SortingActionsForQuery<$model[]>;\n")
        }
        append("\n    constructor(readonly query: $className) {\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("        this._$name = new SortingActionsForQuery<$model[]>('$name', query);\n")
        }
        append("    }\n")
        if (properties.isNotEmpty()) append("\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("    get $name(): SortingActionsForQuery<$model[]> {\n        return this._$name;\n    }\n")
        }
        if (properties.isEmpty()) append("\n")
        append("}\n\nclass ${className}SortByWithoutQuery {\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("    private _$name: SortingActions  = new SortingActions('$name');\n")
        }
        append("\n")
        properties.forEach { property ->
            val name = lowerCamel(property)
            append("    get $name(): SortingActions {\n        return this._$name;\n    }\n")
        }
        append("}\n")
        if (properties.isEmpty()) append("\n")
    }

    private fun StringBuilder.appendQueryHooks(
        query: QueryDescriptor,
        className: String,
        model: String,
        hasParameters: Boolean
    ) {
        val argsType = if (hasParameters) "${className}Parameters" else null
        val argsDeclaration = argsType?.let { "args?: $it" }
        val argsValue = if (hasParameters) "args" else "undefined"
        val genericParameter = argsType?.let { ", $it" }.orEmpty()
        val performType = "PerformQuery${argsType?.let { "<$it>" }.orEmpty()}"
        if (query.isEnumerable) {
            val sortingArgument = "sorting?: Sorting".takeIf { query.supportsSorting }
            val firstArguments = listOfNotNull(argsDeclaration, sortingArgument).joinToString(", ")
            val useTypes = listOf("QueryResultWithState<$model[]>", performType) +
                listOfNotNull("SetSorting".takeIf { query.supportsSorting })
            append("\n    static use($firstArguments): [${useTypes.joinToString(", ")}] {\n")
            if (query.supportsSorting) {
                append("        return useQuery<$model[], $className$genericParameter>($className, $argsValue, sorting);\n    }\n")
            } else {
                append("        const [result, perform] = useQuery<$model[], $className$genericParameter>($className, $argsValue);\n")
                append("        return [result, perform];\n    }\n")
            }
            if (query.supportsPaging) {
                val pagingArguments = listOf("pageSize: number") + listOfNotNull(argsDeclaration, sortingArgument)
                val pagingTypes = listOf("QueryResultWithState<$model[]>", "PerformQuery") +
                    listOfNotNull("SetSorting".takeIf { query.supportsSorting }) +
                    listOf("SetPage", "SetPageSize")
                val sortingValue = if (query.supportsSorting) "sorting" else "undefined"
                append("\n    static useWithPaging(${pagingArguments.joinToString(", ")}): [${pagingTypes.joinToString(", ")}] {\n")
                if (query.supportsSorting) {
                    append("        return useQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n    }\n")
                } else {
                    append("        const [result, perform, , setPage, setPageSize] = useQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n")
                    append("        return [result, perform, setPage, setPageSize];\n    }\n")
                }
            }
            append("\n    static useSuspense($firstArguments): [${useTypes.joinToString(", ")}] {\n")
            if (query.supportsSorting) {
                append("        return useSuspenseQuery<$model[], $className$genericParameter>($className, $argsValue, sorting);\n    }\n")
            } else {
                append("        const [result, perform] = useSuspenseQuery<$model[], $className$genericParameter>($className, $argsValue);\n")
                append("        return [result, perform];\n    }\n")
            }
            if (query.supportsPaging) {
                val pagingArguments = listOf("pageSize: number") + listOfNotNull(argsDeclaration, sortingArgument)
                val pagingTypes = listOf("QueryResultWithState<$model[]>", "PerformQuery") +
                    listOfNotNull("SetSorting".takeIf { query.supportsSorting }) +
                    listOf("SetPage", "SetPageSize")
                val sortingValue = if (query.supportsSorting) "sorting" else "undefined"
                append("\n    static useSuspenseWithPaging(${pagingArguments.joinToString(", ")}): [${pagingTypes.joinToString(", ")}] {\n")
                if (query.supportsSorting) {
                    append("        return useSuspenseQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n    }\n")
                } else {
                    append("        const [result, perform, , setPage, setPageSize] = useSuspenseQueryWithPaging<$model[], $className>($className, new Paging(0, pageSize), $argsValue, $sortingValue);\n")
                    append("        return [result, perform, setPage, setPageSize];\n    }\n")
                }
            }
            append("\n    static when(condition: boolean): QueryWhen<$className, $model[]$genericParameter> {\n")
            append("        return new QueryWhen<$className, $model[]$genericParameter>($className, condition);\n    }\n")
        } else {
            val declaration = argsDeclaration.orEmpty()
            append("\n    static use($declaration): [QueryResultWithState<$model>, $performType, SetSorting] {\n")
            append("        return useQuery<$model, $className$genericParameter>($className${if (hasParameters) ", args" else ""});\n    }\n")
            append("\n    static useSuspense($declaration): [QueryResultWithState<$model>, $performType, SetSorting] {\n")
            append("        return useSuspenseQuery<$model, $className$genericParameter>($className${if (hasParameters) ", args" else ""});\n    }\n")
            append("\n    static when(condition: boolean): QueryWhen<$className, $model$genericParameter> {\n")
            append("        return new QueryWhen<$className, $model$genericParameter>($className, condition);\n    }\n")
        }
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
            ).map(TypeScriptValueImport::name)
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
        val downward = (target.directory.drop(common) + target.typeScriptName).joinToString("/")
        return if (upward.isEmpty()) "./$downward" else "$upward$downward"
    }

    private fun resolvePropertyType(property: PropertyDescriptor, current: ArtifactTarget): TypeScriptType =
        if (property.shape.kind == TypeShapeKind.MAP) {
            resolveMapShape(property.shape, property.name)
        } else {
            resolveType(if (property.isEnumerable) requireElementType(property.name, property.elementTypeName) else property.typeName, current)
        }

    private fun propertyTypeName(property: PropertyDescriptor, current: ArtifactTarget): String =
        resolvePropertyType(property, current).name + if (property.isEnumerable) "[]" else ""

    private fun resolveMapShape(
        shape: TypeShapeDescriptor,
        propertyName: String,
        path: String = propertyName,
        root: Boolean = true
    ): TypeScriptType {
        if (!root && shape.nullable) {
            throw GradleException("Map property '$propertyName' entry path '$path' cannot be nullable.")
        }
        return when (shape.kind) {
            TypeShapeKind.VALUE -> {
                val typeName = requireNotNull(shape.typeName)
                if (typeName !in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
                    throw GradleException(
                        "Map property '$propertyName' entry path '$path' has unsupported value leaf '$typeName'."
                    )
                }
                primitiveTypes[typeName] ?: throw GradleException(
                    "Map property '$propertyName' entry path '$path' has no TypeScript primitive mapping for '$typeName'."
                )
            }
            TypeShapeKind.SEQUENCE -> {
                val element = resolveMapShape(requireNotNull(shape.elementShape), propertyName, "$path[]", false)
                TypeScriptType("${element.name}[]", element.constructor, nestedTypes = listOf(element))
            }
            TypeShapeKind.MAP -> {
                val key = requireNotNull(shape.keyShape)
                if (shape.keyCodec != MapKeyCodec.STRING || key.nullable || key.kind != TypeShapeKind.VALUE ||
                    key.typeName !in MAP_STRING_TYPE_NAMES
                ) {
                    throw GradleException("Map property '$propertyName' entry path '$path.key' must use nonnullable String keys.")
                }
                val value = resolveMapShape(requireNotNull(shape.valueShape), propertyName, "$path.value", false)
                TypeScriptType("Record<string, ${value.name}>", "Object", nestedTypes = listOf(value))
            }
        }
    }

    private fun TypeScriptType.recursiveTypes(): List<TypeScriptType> = listOf(this) + nestedTypes.flatMap {
        nested -> nested.recursiveTypes()
    }

    private fun resolveParameterType(parameter: ParameterDescriptor, current: ArtifactTarget): TypeScriptType =
        resolveType(if (parameter.isEnumerable) requireElementType(parameter.name, parameter.elementTypeName) else parameter.typeName, current)

    private fun resolveDerivatives(property: PropertyDescriptor, current: ArtifactTarget): List<TypeScriptType> =
        property.derivatives.map { derivativeName ->
            val derivative = resolveType(derivativeName, current)
            if (derivative.target?.kind != ArtifactKind.TYPE) {
                throw GradleException(
                    "Arc derivative '$derivativeName' on '${current.sourceName}.${property.name}' must be a generated concrete model type."
                )
            }
            derivative
        }

    private fun resolveBaseType(baseTypeName: String, current: ArtifactTarget): TypeScriptType {
        val baseType = resolveType(baseTypeName, current)
        if (baseType.target?.kind != ArtifactKind.TYPE) {
            throw GradleException("Arc base type '$baseTypeName' on '${current.sourceName}' must be a generated model type.")
        }
        return baseType
    }

    private fun requireElementType(name: String, elementTypeName: String?): String =
        elementTypeName ?: throw GradleException("Enumerable '$name' does not declare an elementTypeName.")

    private fun resolveType(rawTypeName: String, current: ArtifactTarget): TypeScriptType =
        resolveType(rawTypeName, current, mutableSetOf())

    private fun resolveType(
        rawTypeName: String,
        current: ArtifactTarget,
        concepts: MutableSet<String>
    ): TypeScriptType {
        val typeName = rawTypeName.removeSuffix("?")
        val primitiveType = primitiveTypes[typeName]
        if (primitiveType != null) return primitiveType
        conceptsBySource[typeName]?.let { concept ->
            if (!concepts.add(typeName)) {
                throw GradleException("Cyclic Arc concept metadata for '$typeName' in '${current.sourceName}'.")
            }
            return resolveType(concept.underlyingTypeName, current, concepts)
        }
        if (typeName.contains('<') || typeName.contains('>') || isMapType(typeName)) {
            throw GradleException("Unsupported generic or map type '$rawTypeName' in '${current.sourceName}'.")
        }
        val target = targetBySource[typeName]
            ?: throw GradleException("Unsupported Arc proxy type '$rawTypeName' in '${current.sourceName}'.")
        val constructor = when (target.kind) {
            ArtifactKind.ENUM -> "Number"
            ArtifactKind.INTERFACE -> "Object"
            else -> target.typeScriptName
        }
        return TypeScriptType(target.typeScriptName, constructor, target)
    }

    private fun TypeScriptType.globalRuntimeConstructor(): String? {
        if (constructor !in GLOBAL_RUNTIME_CONSTRUCTORS) return null
        return constructor.takeIf {
            target == null || target.kind == ArtifactKind.INTERFACE || target.kind == ArtifactKind.ENUM
        }
    }

    private fun isMapType(typeName: String): Boolean =
        typeName == "kotlin.collections.Map" || typeName == "java.util.Map" || typeName.endsWith("Map")

    private fun StringBuilder.appendGeneratedHeader() {
        append("/*---------------------------------------------------------------------------------------------\n")
        append(" *  **DO NOT EDIT** - This file is an automatically generated file.\n")
        append(" *--------------------------------------------------------------------------------------------*/\n\n")
    }

    private fun validateOutputTree() {
        val output = options.outputDirectory
        if (output.exists() && Files.isSymbolicLink(output.toPath())) {
            throw GradleException("Arc proxy output directory must not be a symbolic link: '${output.path}'.")
        }
        if (output.isDirectory) {
            output.walkTopDown().firstOrNull { candidate -> Files.isSymbolicLink(candidate.toPath()) }?.let { link ->
                throw GradleException("Arc proxy output must not contain symbolic links: '${link.path}'.")
            }
        }
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

    private fun writeGenerated(file: File, source: String, body: String) {
        val normalizedBody = body.replace("\r\n", "\n").trimEnd() + "\n"
        val hash = sha256(normalizedBody)
        val content = "// @generated by Cratis. Source: $source. Hash: $hash\n$normalizedBody"
        if (file.isFile) {
            val existing = file.readText()
            if (existing == content) return
            if (!hasGeneratedMarker(existing)) {
                throw GradleException("Refusing to overwrite hand-written TypeScript file '${file.path}'.")
            }
        }
        file.parentFile.mkdirs()
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8)
    }

    private fun removeStale(expectedFiles: Set<File>): Set<String> {
        val removed = mutableSetOf<String>()
        options.outputDirectory.walkBottomUp().forEach { file ->
            if (
                file.isFile && file.extension == "ts" && file.name != "index.ts" &&
                file.canonicalFile !in expectedFiles && hasGeneratedMarker(file.readText())
            ) {
                removed += file.relativeTo(options.outputDirectory).invariantSeparatorsPath
                file.delete()
            }
        }
        return removed
    }

    private fun updateIndexFiles(generatedPaths: Set<String>, removedFiles: Set<String>): List<File> {
        val generatedByDirectory = generatedPaths.groupBy { it.substringBeforeLast('/', "") }
            .mapValues { (_, paths) -> paths.map { it.substringAfterLast('/').removeSuffix(".ts") }.toSet() }
        val removedByDirectory = removedFiles.groupBy { path ->
            path.substringBeforeLast('/', "")
        }.mapValues { (_, paths) -> paths.map { it.substringAfterLast('/').removeSuffix(".ts") }.toSet() }
        val directories = (generatedByDirectory.keys + removedByDirectory.keys).toMutableSet()
        if (options.outputDirectory.isDirectory) {
            options.outputDirectory.walkTopDown().filter { it.isFile && it.name == "index.ts" }.forEach { index ->
                directories += index.parentFile.relativeTo(options.outputDirectory).invariantSeparatorsPath
                    .takeUnless { it == "." }.orEmpty()
            }
        }

        return directories.sorted().mapNotNull { directory ->
            updateIndexFile(
                directory,
                generatedByDirectory[directory].orEmpty(),
                removedByDirectory[directory].orEmpty()
            )
        }
    }

    private fun updateIndexFile(directory: String, generatedNames: Set<String>, removedNames: Set<String>): File? {
        val targetDirectory = if (directory.isEmpty()) options.outputDirectory else File(options.outputDirectory, directory)
        val index = File(targetDirectory, "index.ts")
        val existing = index.takeIf(File::isFile)?.readText()?.replace("\r\n", "\n")
        val generatedIndex = existing != null && hasGeneratedMarker(existing)
        val lines = existing?.lines().orEmpty().let { if (generatedIndex) it.drop(1) else it }
        val exports = mutableListOf<String>()
        val manualLines = mutableListOf<String>()
        lines.forEach { line ->
            val match = INDEX_EXPORT.matchEntire(line)
            if (match != null) {
                exports += match.groupValues[1]
            } else if (line.isNotBlank()) {
                manualLines += line
            }
        }

        val removed = removedNames.map(String::lowercase).toSet()
        val finalExports = exports.filterNotTo(mutableListOf()) { export ->
            export.trimStart('.', '/').lowercase() in removed
        }
        val existingNames = finalExports.map { it.trimStart('.', '/') }.toMutableSet()
        generatedNames.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { name ->
            if (existingNames.none { it.equals(name, ignoreCase = true) }) {
                val insertion = finalExports.indexOfFirst {
                    name.compareTo(it.trimStart('.', '/'), ignoreCase = true) < 0
                }.takeIf { it >= 0 } ?: finalExports.size
                finalExports.add(insertion, "./$name")
                existingNames += name
            }
        }

        if (generatedNames.isEmpty() && finalExports.isEmpty()) {
            if (generatedIndex || (manualLines.isEmpty() && removedNames.isNotEmpty())) index.delete()
            return null
        }
        if (generatedNames.isEmpty() && !generatedIndex && removedNames.isEmpty()) return index.takeIf(File::isFile)

        val body = (manualLines + finalExports.map { "export * from '$it';" }).joinToString("\n", postfix = "\n")
        val normalized = body.replace("\r\n", "\n").trimEnd() + "\n"
        if (existing != normalized) Files.writeString(index.toPath(), normalized, StandardCharsets.UTF_8)
        return index
    }

    private fun removeEmptyDirectories() {
        if (!options.outputDirectory.isDirectory) return
        options.outputDirectory.walkBottomUp().forEach { directory ->
            if (directory.isDirectory && directory != options.outputDirectory && directory.list()?.isEmpty() == true) {
                directory.delete()
            }
        }
    }

    private fun hasGeneratedMarker(content: String): Boolean =
        GENERATED_MARKER.matches(content.lineSequence().firstOrNull().orEmpty())

    private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02X".format(it) }

    private fun roles(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { "'${escape(it)}'" }
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    private fun lowerCamel(value: String): String = requireNotNull(ArcCamelCase.convert(value))
    private fun upperCamel(value: String): String = value.replaceFirstChar(Char::uppercaseChar)

    private companion object {
        const val FUNDAMENTALS_PACKAGE = "@cratis/fundamentals"
        val FUNDAMENTALS_IMPORT_ORDER = mapOf("field" to 0, "derivedType" to 1)
        val GENERATED_MARKER = Regex(
            "^// @generated by Cratis\\. Source: .+\\. (?:Time: .+\\. )?Hash: [A-Fa-f0-9]{64}$"
        )
        val SAFE_PATH_SEGMENT = Regex("^[A-Za-z0-9_$-]+$")
        val TYPESCRIPT_IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
        val INDEX_EXPORT = Regex("^\\s*export\\s+\\*\\s+from\\s+['\"](.+)['\"]\\s*;?\\s*$")
        val ROUTE_PARAMETER = Regex("\\{([^}:]+)(?::[^}]+)?}")
        val GLOBAL_RUNTIME_CONSTRUCTORS = setOf("Boolean", "Date", "Number", "Object", "String")
        val MAP_STRING_TYPE_NAMES = setOf("kotlin.String", "java.lang.String", "String")
        val MAP_SAFE_PRIMITIVE_TYPE_NAMES = setOf(
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Int", "kotlin.Short", "kotlin.String",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Integer", "java.lang.Short",
            "java.lang.String", "boolean", "byte", "char", "int", "short", "String", "Boolean"
        )

        val primitiveTypes: Map<String, TypeScriptType> = buildMap {
            listOf(
                "kotlin.String", "java.lang.String", "String", "kotlin.Char", "java.lang.Character", "char", "Char"
            ).forEach { put(it, TypeScriptType("string", "String")) }
            listOf("java.util.UUID", "UUID").forEach {
                put(it, TypeScriptType("Guid", "Guid", valueImport = TypeScriptValueImport(FUNDAMENTALS_PACKAGE, "Guid")))
            }
            listOf("kotlin.Boolean", "java.lang.Boolean", "boolean", "Boolean").forEach {
                put(it, TypeScriptType("boolean", "Boolean"))
            }
            listOf(
                "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double",
                "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
                "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float",
                "java.lang.Double", "byte", "short", "int", "long", "float", "double", "Number",
                "java.math.BigDecimal", "java.math.BigInteger", BigInteger::class.java.name
            ).forEach { put(it, TypeScriptType("number", "Number")) }
            listOf("java.time.LocalDate", "LocalDate").forEach {
                put(
                    it,
                    TypeScriptType(
                        "DateOnly",
                        "DateOnly",
                        valueImport = TypeScriptValueImport(FUNDAMENTALS_PACKAGE, "DateOnly")
                    )
                )
            }
            listOf(
                "java.time.LocalDateTime", "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime",
                "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime"
            ).forEach { put(it, TypeScriptType("Date", "Date")) }
            listOf("java.time.LocalTime", "LocalTime").forEach {
                put(
                    it,
                    TypeScriptType(
                        "TimeOnly",
                        "TimeOnly",
                        valueImport = TypeScriptValueImport(FUNDAMENTALS_PACKAGE, "TimeOnly")
                    )
                )
            }
            listOf(
                "java.time.OffsetTime", "java.time.Duration", "java.time.Period", "OffsetTime", "Duration", "Period"
            ).forEach { put(it, TypeScriptType("string", "String")) }
        }
    }
}
