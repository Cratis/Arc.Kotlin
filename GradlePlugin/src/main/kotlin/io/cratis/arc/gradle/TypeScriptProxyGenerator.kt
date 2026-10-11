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

    private val model = TypeScriptGenerationModel(artifacts, options, useProxyFileSuffix)
    private val shared = model.shared
    private val emittedTypes = model.emittedTypes
    private val emittedInterfaces = model.emittedInterfaces
    private val emittedEnums = model.emittedEnums
    private val targetBySource = model.targetBySource
    private val validators = TypeScriptValidatorRenderer(shared)
    private val outputTree = TypeScriptOutputTree(options.outputDirectory)
    private val resolver = TypeScriptTypeResolver(model)
    private val artifactValidator = TypeScriptArtifactValidator(model, resolver)

    fun generate(): List<File> {
        require(options.segmentsToSkip >= 0) { "Proxy segmentsToSkip cannot be negative." }
        outputTree.validate()
        artifactValidator.validate()
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
            val target = model.queryTarget(query)
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

    private fun renderCommand(command: CommandDescriptor, target: ArtifactTarget, route: String): String {
        val propertyTypes = command.properties.map { resolver.resolvePropertyType(it, target) }
        val response = command.responseTypeName?.let { resolver.resolveType(it, target) }
        val referencedTypes = propertyTypes + listOfNotNull(response)
        val validationTargets = command.properties.map { property ->
            ValidationTarget(lowerCamel(property.name), property.validationRules.clientRepresentable().filterNot { rule -> shared.rules(command.typeName, property.name).any { sameRule(rule, it) } })
        }.filter(ValidationTarget::hasRules).sortedBy(ValidationTarget::propertyName)
        val context = CommandRenderContext(
            command, target, "I${command.name}", response, validationTargets, shared.contains(command.typeName)
        )
        return buildString {
            appendGeneratedHeader()
            appendCommandImports(context, referencedTypes)
            appendCommandInterface(context)
            appendCommandValidator(context)
            appendCommandClass(context, route)
        }
    }

    /** The command being rendered and the facts every section of its proxy depends on. */
    private class CommandRenderContext(
        val command: CommandDescriptor,
        val target: ArtifactTarget,
        val interfaceName: String,
        val response: TypeScriptType?,
        val validationTargets: List<ValidationTarget>,
        val sharedValidation: Boolean
    ) {
        val hasValidation: Boolean get() = validationTargets.isNotEmpty() || sharedValidation
    }

    private fun StringBuilder.appendCommandImports(context: CommandRenderContext, referencedTypes: List<TypeScriptType>) {
        val command = context.command
        val imports = customImports(referencedTypes, referencedTypes)
        append("/* eslint-disable sort-imports */\n")
        append("/* eslint-disable @typescript-eslint/no-empty-interface */\n")
        append("// eslint-disable-next-line header/header\n")
        append("import { Command${if (context.hasValidation) ", CommandValidator" else ""} } from '@cratis/arc/commands';\n")
        append("import { useCommand, type SetCommandValues, type ClearCommandValues } from '@cratis/arc.react/commands';\n")
        append("import { PropertyDescriptor } from '@cratis/arc/reflection';\n")
        appendPackageImports(referencedTypes)
        appendCustomImports(imports, context.target)
        if (context.sharedValidation) {
            appendSharedImports(context.target, command.properties.filterNot { it.ignoreValidation }.map { it.shape })
        }
        append("\n")
        if (command.properties.any { property -> property.shape.containsMap() }) appendStringMapGuard()
    }

    private fun StringBuilder.appendCommandInterface(context: CommandRenderContext) {
        appendDocumentation(context.command.summary)
        append("export interface ${context.interfaceName} {\n")
        context.command.properties.forEach { property ->
            appendDocumentation(property.summary, "    ")
            append("    ${lowerCamel(property.name)}?: ${resolver.propertyTypeName(property, context.target)};\n")
        }
        append("}\n\n")
    }

    private fun StringBuilder.appendCommandValidator(context: CommandRenderContext) {
        val command = context.command
        if (context.sharedValidation) {
            validators.appendSharedValidator(
                this,
                SharedValidatorSpec(
                    "${command.name}Validator", "CommandValidator", context.interfaceName, command.typeName,
                    command.properties, context.validationTargets, context.target
                )
            )
        } else {
            validators.appendValidator(
                this, "${command.name}Validator", "CommandValidator", context.interfaceName, context.validationTargets
            )
        }
    }

    private fun StringBuilder.appendCommandClass(context: CommandRenderContext, route: String) {
        val command = context.command
        val interfaceName = context.interfaceName
        val responseGeneric = context.response?.let {
            ", ${it.name}${if (command.responseIsEnumerable) "[]" else ""}"
        }.orEmpty()
        appendDocumentation(command.summary)
        append("export class ${command.name} extends Command<$interfaceName$responseGeneric> implements $interfaceName {\n")
        appendCommandFields(context, route)
        append("\n    constructor() {\n")
        append("        super(${context.response?.constructor ?: "Object"}, ${command.responseIsEnumerable});\n")
        append("    }\n\n")
        append("    get requestParameters(): string[] {\n        return [\n")
        routeParameters(route, command.properties).forEach { append("            '$it',\n") }
        append("        ];\n    }\n\n")
        appendCommandAccessors(context)
        append("\n    static use(initialValues?: $interfaceName): [${command.name}, SetCommandValues<$interfaceName>, ClearCommandValues] {\n")
        append("        // eslint-disable-next-line @typescript-eslint/ban-ts-comment\n        // @ts-ignore\n")
        append("        return useCommand<${command.name}, $interfaceName>(${command.name}, initialValues);\n    }\n}\n")
    }

    private fun StringBuilder.appendCommandFields(context: CommandRenderContext, route: String) {
        val command = context.command
        append("    readonly route: string = '${escape(route)}';\n")
        if (context.hasValidation) {
            append("    readonly validation: CommandValidator = new ${command.name}Validator();\n")
        }
        append("    readonly treatWarningsAsErrors: boolean = ${command.treatWarningsAsErrors};\n")
        append("    readonly roles: string[] = ${roles(command.authorization.roles)};\n")
        append("    readonly propertyDescriptors: PropertyDescriptor[] = [\n")
        command.properties.forEach { property ->
            val type = resolver.resolvePropertyType(property, context.target)
            append("        new PropertyDescriptor('${lowerCamel(property.name)}', ${type.constructor}, ${property.isNullable}),\n")
        }
        append("    ];\n\n")
        command.properties.forEach { property ->
            val nullable = if (property.isNullable) "?" else "!"
            append("    private _${lowerCamel(property.name)}$nullable: ${resolver.propertyTypeName(property, context.target)};\n")
        }
    }

    private fun StringBuilder.appendCommandAccessors(context: CommandRenderContext) {
        context.command.properties.forEach { property ->
            val name = lowerCamel(property.name)
            val type = resolver.propertyTypeName(property, context.target)
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
    }

    private fun renderType(type: TypeDescriptor, target: ArtifactTarget): String {
        val propertyTypes = type.properties.associateWith { property -> resolver.resolvePropertyType(property, target) }
        val derivativeTypes = type.properties.associateWith { property -> resolver.resolveDerivatives(property, target) }
        val baseType = type.baseTypeName?.let { resolver.resolveBaseType(it, target) }
        val referencedTypes = propertyTypes.values + derivativeTypes.values.flatten() + listOfNotNull(baseType)
        val imports = customImports(referencedTypes, referencedTypes)
        return buildString {
            appendGeneratedHeader()
            append("/* eslint-disable sort-imports */\n")
            append("// eslint-disable-next-line header/header\n")
            appendPackageImports(referencedTypes, typeFundamentalsImports(type))
            appendCustomImports(imports, target)
            if (shared.contains(type.fullyQualifiedName)) appendSharedImports(target, type.properties.filterNot { it.ignoreValidation }.map { it.shape })
            append("\n")
            appendDocumentation(type.summary)
            type.derivedTypeId?.let { append("@derivedType('${escape(it)}')\n") }
            append("export class ${type.name}${baseType?.let { " extends ${it.name}" }.orEmpty()} {\n")
            type.properties.forEach { property ->
                appendTypeField(property, propertyTypes.getValue(property), derivativeTypes.getValue(property), target)
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

    private fun typeFundamentalsImports(type: TypeDescriptor): List<TypeScriptValueImport> = buildList {
        if (type.properties.isNotEmpty()) add(TypeScriptValueImport(FUNDAMENTALS_PACKAGE, "field"))
        if (type.derivedTypeId != null) add(TypeScriptValueImport(FUNDAMENTALS_PACKAGE, "derivedType"))
    }

    private fun StringBuilder.appendTypeField(
        property: PropertyDescriptor,
        resolved: TypeScriptType,
        derivatives: List<TypeScriptType>,
        target: ArtifactTarget
    ) {
        val fieldArguments = buildList {
            add(resolved.constructor)
            if (property.isEnumerable || derivatives.isNotEmpty()) add(property.isEnumerable.toString())
            if (derivatives.isNotEmpty()) add("[${derivatives.joinToString(", ") { it.constructor }}]")
        }
        appendDocumentation(property.summary, "    ")
        append("    @field(${fieldArguments.joinToString(", ")})\n")
        val marker = if (property.isNullable) "?" else "!"
        append("    ${lowerCamel(property.name)}$marker: ${resolver.propertyTypeName(property, target)};\n")
    }

    private fun renderInterface(interfaceDescriptor: InterfaceDescriptor, target: ArtifactTarget): String {
        val referencedTypes = interfaceDescriptor.properties.map { resolver.resolvePropertyType(it, target) }
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
                append("    ${lowerCamel(property.name)}$optional: ${resolver.propertyTypeName(property, target)};\n")
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
        val parameterTypes = parameters.map { resolver.resolveParameterType(it, target) }
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
        appendQueryImportSection(m, observable)
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
        append(spacingBeforeQueryClass(m))
        appendQueryClass(m, observable)
    }

    private fun StringBuilder.appendQueryImportSection(m: QueryRenderModel, observable: Boolean) {
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
    }

    private fun spacingBeforeQueryClass(m: QueryRenderModel): String {
        val bare = m.parameters.isEmpty() && m.validationTargets.isEmpty()
        return when {
            m.query.isEnumerable && bare -> "\n"
            !m.query.isEnumerable && bare -> "\n\n\n"
            else -> "\n\n"
        }
    }

    private fun StringBuilder.appendQueryClass(m: QueryRenderModel, observable: Boolean) {
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
            val array = resolver.parameterArraySuffix(parameter)
            appendDocumentation(parameter.summary, "    ")
            append("    ${lowerCamel(parameter.name)}$optional: ${resolver.resolveParameterType(parameter, m.target).name}$array;\n")
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
            append("        new ParameterDescriptor('${lowerCamel(parameter.name)}', ${resolver.resolveParameterType(parameter, m.target).constructor}, ${parameter.isEnumerable}),\n")
        }
        append("    ];\n\n")
        m.parameters.forEach { parameter ->
            val optional = if (parameter.hasDefault) "?" else "!"
            val array = resolver.parameterArraySuffix(parameter)
            append("    ${lowerCamel(parameter.name)}$optional: ${resolver.resolveParameterType(parameter, m.target).name}$array;\n")
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

    /** Select only declared return-row fields; request arguments are not sortable row members. */
    private fun returnedSortProperties(query: QueryDescriptor): List<String> {
        if (!query.isEnumerable || !query.supportsSorting) return emptyList()
        val names = sortedSetOf<String>()
        collectReturnModelNames(query, names)
        rejectUnsafeSortName(query, names)
        return names.toList()
    }

    private fun collectReturnModelNames(query: QueryDescriptor, names: MutableSet<String>) {
        val visited = mutableSetOf<String>()
        var typeName: String? = query.returnTypeName
        while (typeName != null) {
            if (!visited.add(typeName)) {
                throw GradleException("Cyclic Arc return-model inheritance for '${query.fullyQualifiedName}' at '$typeName'.")
            }
            val model = artifacts.types.firstOrNull { it.fullyQualifiedName == typeName }
            if (model == null) {
                // Interface inheritance is not represented by the manifest; do not invent missing members.
                names += interfaceSortProperties(typeName)
                break
            }
            model.properties.forEach { names += lowerCamel(it.name) }
            typeName = model.baseTypeName
        }
    }

    private fun interfaceSortProperties(typeName: String): List<String> =
        artifacts.interfaces.firstOrNull { it.fullyQualifiedName == typeName }?.properties.orEmpty()
            .map { lowerCamel(it.name) }

    private fun rejectUnsafeSortName(query: QueryDescriptor, names: Collection<String>) {
        names.firstOrNull { it == "constructor" || !TYPESCRIPT_IDENTIFIER.matches(it) }?.let { name ->
            throw GradleException("Cannot generate Arc sorting helper '${query.fullyQualifiedName}.$name'; rename that return-model member or expose a different result contract.")
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

}
