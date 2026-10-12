// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.TypeShapeDescriptor

/** One resolved query parameter together with the facts needed to validate it. */
private class QueryParameterContext(
    val queryName: String,
    val name: String,
    val parameter: KSValueParameter,
    val type: KSType,
    val declaration: KSClassDeclaration,
    val qualifiedName: String
) {
    val hostAdapterKind = QUERY_HOST_ADAPTER_TYPES[qualifiedName]
    val infrastructureSource = QUERY_INFRASTRUCTURE_TYPES[qualifiedName]
        ?: hostAdapterKind?.let { QueryParameterSource.HOST_ADAPTER }

    /** Assigned after the infrastructure-like checks so the annotation is only resolved at the original point. */
    var fromServices = false

    val identity: String get() = "$queryName.$name"

    val parameterSource: QueryParameterSource
        get() = infrastructureSource ?: if (fromServices) QueryParameterSource.SERVICE else QueryParameterSource.CLIENT
}

/** Validates and models the parameters of a single query function. */
internal class QueryParameterBuilder(
    private val logger: ArcDiagnosticReporter,
    private val collector: MetadataCollector,
    private val queryName: String,
    private val function: KSFunctionDeclaration
) {
    private val infrastructureSources = mutableSetOf<QueryParameterSource>()
    private val hostAdapterKinds = mutableSetOf<QueryHostAdapterKind>()

    fun build(): List<QueryParameterModel>? {
        val documentation = DocumentationSummaryParser.parse(function.docString)
        val parameters = mutableListOf<QueryParameterModel>()
        function.parameters.forEach { parameter ->
            val context = contextFor(parameter) ?: return null
            if (!validate(context)) return null
            parameters.add(createModel(context, documentation) ?: return null)
        }
        return parameters
    }

    private fun validate(context: QueryParameterContext): Boolean {
        if (!validateInfrastructureLike(context)) return false
        context.fromServices = context.parameter.hasAnnotation(FROM_SERVICES_ANNOTATION)
        if (context.infrastructureSource != null && !validateInfrastructure(context)) return false
        return validateClientOrService(context)
    }

    private fun contextFor(parameter: KSValueParameter): QueryParameterContext? {
        val name = parameter.name?.asString()
        if (name == null) {
            logger.error("Query '$queryName' has an unnamed parameter.", parameter)
            return null
        }
        if (parameter.isVararg) {
            logger.error("Query parameter '$queryName.$name' must not be variadic.", parameter)
            return null
        }
        val type = parameter.type.resolve()
        val declaration = type.declaration as? KSClassDeclaration
        val qualifiedName = declaration?.qualifiedName?.asString()
        if (type.isError || declaration == null || qualifiedName == null) {
            logger.error("Query parameter '$queryName.$name' must have a resolvable class or interface type.", parameter)
            return null
        }
        return QueryParameterContext(queryName, name, parameter, type, declaration, qualifiedName)
    }

    private fun fail(context: QueryParameterContext, diagnostic: ArcDiagnostic, message: String): Boolean {
        logger.error(diagnostic, message, context.parameter)
        return false
    }

    private fun failUnclassified(context: QueryParameterContext, message: String): Boolean {
        logger.error(message, context.parameter)
        return false
    }

    private fun validateInfrastructureLike(context: QueryParameterContext): Boolean {
        val supertypes = context.declaration.getAllSuperTypes()
            .mapNotNull { supertype -> supertype.declaration.qualifiedName?.asString() }
            .toList()
        val simpleName = context.declaration.simpleName.asString()
        val infrastructureLike = simpleName in QUERY_INFRASTRUCTURE_SIMPLE_NAMES ||
            supertypes.firstOrNull(QUERY_INFRASTRUCTURE_TYPES::containsKey) != null
        val hostAdapterLike = simpleName in QUERY_HOST_ADAPTER_SIMPLE_NAMES ||
            supertypes.firstOrNull(QUERY_HOST_ADAPTER_TYPES::containsKey) != null
        if (context.infrastructureSource == null && infrastructureLike) {
            return fail(
                context,
                ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER,
                "Query parameter '${context.identity}' uses infrastructure-like type '${context.qualifiedName}'; only exact " +
                    "non-null '$QUERY_REQUEST_TYPE' and '$QUERY_CONTEXT_TYPE' types are supported as query " +
                    "infrastructure parameters."
            )
        }
        if (context.hostAdapterKind == null && hostAdapterLike) {
            return fail(
                context,
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query parameter '${context.identity}' uses host-adapter-like type '${context.qualifiedName}'; only exact " +
                    "non-null '$SPRING_PAGEABLE_TYPE' and '$SPRING_SORT_TYPE' host adapter parameters are supported."
            )
        }
        return true
    }

    private fun validateInfrastructure(context: QueryParameterContext): Boolean =
        validateInfrastructureType(context) &&
            validateInfrastructureNotFromServices(context) &&
            validateInfrastructureDefault(context) &&
            registerInfrastructure(context)

    private fun infrastructureDiagnostic(context: QueryParameterContext): ArcDiagnostic =
        if (context.hostAdapterKind == null) {
            ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER
        } else {
            ArcDiagnostic.HOST_ADAPTER_PARAMETER
        }

    private fun infrastructureKind(context: QueryParameterContext): String =
        if (context.hostAdapterKind == null) "Query infrastructure" else "Query host adapter"

    private fun validateInfrastructureType(context: QueryParameterContext): Boolean {
        val diagnostic = infrastructureDiagnostic(context)
        val parameterKind = infrastructureKind(context)
        if (context.type.arguments.isNotEmpty()) {
            return fail(
                context,
                diagnostic,
                "$parameterKind parameter '${context.identity}' must use the exact non-parameterized type '${context.qualifiedName}'."
            )
        }
        if (context.type.nullability == Nullability.NULLABLE || context.parameter.hasNullableAnnotation()) {
            return fail(
                context,
                diagnostic,
                "$parameterKind parameter '${context.identity}' must use the exact non-null type '${context.qualifiedName}'."
            )
        }
        return true
    }

    private fun validateInfrastructureNotFromServices(context: QueryParameterContext): Boolean {
        if (!context.fromServices) return true
        val supplier = if (context.hostAdapterKind == null) {
            "supplied by the query execution context."
        } else {
            "supplied from the query request."
        }
        return fail(
            context,
            infrastructureDiagnostic(context),
            "${infrastructureKind(context)} parameter '${context.identity}' must not be annotated @FromServices; " +
                "its value is $supplier"
        )
    }

    private fun validateInfrastructureDefault(context: QueryParameterContext): Boolean {
        if (function.origin != Origin.KOTLIN || !context.parameter.hasDefault) return true
        if (context.hostAdapterKind == null) {
            return fail(
                context,
                ArcDiagnostic.QUERY_DEFAULT,
                "Kotlin query parameter default '${context.identity}' is unsupported because " +
                    "QUERY_REQUEST and QUERY_CONTEXT infrastructure parameters must always be supplied by Arc."
            )
        }
        return fail(
            context,
            ArcDiagnostic.HOST_ADAPTER_PARAMETER,
            "Kotlin query host adapter parameter '${context.identity}' must not declare a " +
                "default value; Arc always creates it from the query request."
        )
    }

    private fun registerInfrastructure(context: QueryParameterContext): Boolean {
        val hostAdapterKind = context.hostAdapterKind
        if (hostAdapterKind != null) {
            if (hostAdapterKinds.add(hostAdapterKind)) return true
            return fail(
                context,
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query '$queryName' declares more than one ${hostAdapterKind.name} host adapter parameter; " +
                    "each host adapter kind may appear at most once."
            )
        }
        val infrastructureSource = requireNotNull(context.infrastructureSource)
        if (infrastructureSources.add(infrastructureSource)) return true
        return fail(
            context,
            ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER,
            "Query '$queryName' declares more than one ${infrastructureSource.name} infrastructure " +
                "parameter; each infrastructure source may appear at most once."
        )
    }

    private fun validateClientOrService(context: QueryParameterContext): Boolean =
        validateNotReservedName(context) &&
            validateNoMap(context) &&
            validateConcreteClientType(context) &&
            (!context.fromServices || validateService(context))

    private fun validateNotReservedName(context: QueryParameterContext): Boolean {
        val isClient = context.infrastructureSource == null && context.hostAdapterKind == null && !context.fromServices
        if (!isClient || context.name.lowercase() !in RESERVED_QUERY_PARAMETER_NAMES) return true
        return fail(
            context,
            ArcDiagnostic.HOST_ADAPTER_PARAMETER,
            "Query client parameter '${context.identity}' conflicts with reserved paging or sorting control " +
                "'${context.name}'; use another parameter name."
        )
    }

    private fun validateNoMap(context: QueryParameterContext): Boolean {
        if (!containsMapType(context.type)) return true
        return fail(
            context,
            ArcDiagnostic.PROXY_SHAPE,
            "Query '$queryName' parameter '${context.name}' value path 'parameter' uses a map; query, observable, and service parameters cannot use maps."
        )
    }

    private fun validateConcreteClientType(context: QueryParameterContext): Boolean {
        if (context.infrastructureSource != null || context.fromServices || !isAbstractType(context.declaration)) return true
        return fail(
            context,
            ArcDiagnostic.QUERY_PARAMETER,
            "Query parameter '${context.identity}' has service-like interface or abstract type '${context.qualifiedName}'; " +
                "annotate it with @FromServices or use a concrete client-serializable parameter type."
        )
    }

    private fun isAbstractType(declaration: KSClassDeclaration): Boolean =
        declaration.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in declaration.modifiers

    private fun validateService(context: QueryParameterContext): Boolean {
        if (context.type.nullability == Nullability.NULLABLE) {
            return fail(
                context,
                ArcDiagnostic.QUERY_PARAMETER,
                "Query service parameter '${context.identity}' must be non-null because generated service resolution is required."
            )
        }
        if (function.origin == Origin.KOTLIN && context.parameter.hasDefault) {
            return fail(
                context,
                ArcDiagnostic.QUERY_DEFAULT,
                "Kotlin query parameter default '${context.identity}' is unsupported because service parameters must " +
                    "always be supplied by Arc."
            )
        }
        if (context.type.arguments.isNotEmpty()) {
            return failUnclassified(context, "Query service parameter '${context.identity}' must not use a parameterized type.")
        }
        if (context.qualifiedName == "kotlin.Array") {
            return failUnclassified(context, "Query service parameter '${context.identity}' must not use an array type.")
        }
        return true
    }

    private fun createModel(context: QueryParameterContext, documentation: ParsedDocumentation): QueryParameterModel? {
        val source = context.parameterSource
        val shape = describeShape(context, source) ?: return null
        val renderedName = renderKotlinType(context.type)
        if (renderedName == null) {
            logger.error("Query parameter '${context.identity}' has an unsupported type.", context.parameter)
            return null
        }
        if (source == QueryParameterSource.CLIENT && !collector.collect(shape, context.identity, context.parameter)) return null
        val validation = collector.extractValidation(
            listOf(context.parameter),
            shape,
            context.identity,
            context.parameter
        ) ?: return null
        if (!validateValidation(context, source, validation)) return null
        return toModel(context, shape, renderedName, validation, documentation.paramTags[context.name])
    }

    private fun toModel(
        context: QueryParameterContext,
        shape: TypeShape,
        renderedName: String,
        validation: ValidationMetadata,
        documentedSummary: String?
    ): QueryParameterModel {
        val source = context.parameterSource
        val isClient = source == QueryParameterSource.CLIENT
        return QueryParameterModel(
            name = context.name,
            typeName = shape.typeName,
            shape = shape.descriptor,
            renderedTypeName = renderedName,
            erasedTypeName = renderRuntimeType(context.qualifiedName),
            source = source,
            hostAdapterKind = context.hostAdapterKind,
            hasDefault = function.origin == Origin.KOTLIN && isClient && context.parameter.hasDefault,
            isNullable = shape.isNullable,
            isEnumerable = shape.isEnumerable,
            elementTypeName = shape.elementTypeName,
            validationRules = validation.rules,
            validateRecursively = validation.validateRecursively,
            // Only a client parameter reaches a generated proxy, so only it carries documentation.
            summary = documentedSummary.takeIf { isClient }
        )
    }

    private fun describeShape(context: QueryParameterContext, source: QueryParameterSource): TypeShape? {
        if (source == QueryParameterSource.CLIENT) {
            return collector.describe(context.type, context.identity, context.parameter)
        }
        return TypeShape(
            context.qualifiedName,
            TypeShapeDescriptor.value(context.qualifiedName, context.type.nullability == Nullability.NULLABLE),
            context.type
        )
    }

    private fun validateValidation(
        context: QueryParameterContext,
        source: QueryParameterSource,
        validation: ValidationMetadata
    ): Boolean {
        val javaClientConstraint = function.origin == Origin.JAVA && source == QueryParameterSource.CLIENT &&
            validation.rules.isNotEmpty()
        if (javaClientConstraint) {
            return fail(
                context,
                ArcDiagnostic.VALIDATION,
                "Jakarta constraints declared directly on static Java query parameter '${context.identity}' are " +
                    "unsupported because Jakarta executable validation requires an invocation receiver; use a " +
                    "QueryValidator or a validated argument model instead."
            )
        }
        val serviceValidation = source == QueryParameterSource.SERVICE &&
            (validation.rules.isNotEmpty() || validation.validateRecursively)
        if (serviceValidation) {
            return fail(
                context,
                ArcDiagnostic.VALIDATION,
                "Validation annotations on service parameter '${context.identity}' cannot be represented in a client proxy."
            )
        }
        return true
    }
}
