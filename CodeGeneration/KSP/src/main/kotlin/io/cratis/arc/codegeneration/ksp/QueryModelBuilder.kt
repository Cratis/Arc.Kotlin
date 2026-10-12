// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import io.cratis.arc.metadata.QueryParameterSource

/** One query function of a read model. */
private class QuerySite(val readModel: KSClassDeclaration, val function: KSFunctionDeclaration) {
    val declaringTypeName: String = requireNotNull(readModel.qualifiedName).asString()
    val methodName: String = function.simpleName.asString()
    val identity: String = "$declaringTypeName.$methodName"
}

/** Validates one query function and builds its model. */
internal class QueryModelBuilder(
    private val logger: ArcDiagnosticReporter,
    private val collector: MetadataCollector,
    private val resolver: Resolver,
    private val authorizationReader: AuthorizationReader
) {
    fun build(readModel: KSClassDeclaration, function: KSFunctionDeclaration): QueryModel? {
        val site = QuerySite(readModel, function)
        if (!validateDeclaration(site)) return null

        val parameters = QueryParameterBuilder(logger, collector, site.identity, function).build() ?: return null
        if (!validateDefaultedParameters(site, parameters)) return null
        val returnShape = QueryReturnShapeAnalyzer(logger, resolver, readModel, function).analyze() ?: return null
        if (!validateHostAdapters(site, parameters, returnShape)) return null
        val authorization = authorizationReader.read(readModel, function, site.identity, "Query") ?: return null
        val pathResolution = resolveExplicitPath(site)
        if (!pathResolution.first) return null
        val queryHttpMethod = function.enumArgument(QUERY_HTTP_METHOD_ANNOTATION, "value")
            ?: readModel.enumArgument(QUERY_HTTP_METHOD_ANNOTATION, "value")
            ?: "AUTO"
        val transport = resolveTransport(site, returnShape) ?: return null

        return QueryModel(
            declaringTypeName = site.declaringTypeName,
            methodName = site.methodName,
            performerClassName = queryPerformerClassName(site.identity),
            parameters = parameters,
            returnTypeName = site.declaringTypeName,
            authorization = authorization,
            explicitPath = pathResolution.second,
            queryHttpMethod = queryHttpMethod,
            transport = transport,
            isEnumerable = returnShape.isEnumerable,
            supportsPaging = returnShape.supportsPaging ||
                parameters.any { parameter -> parameter.hostAdapterKind == QueryHostAdapterKind.PAGEABLE },
            supportsSorting = parameters.any { parameter -> parameter.hostAdapterKind != null },
            treatWarningsAsErrors = readModel.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION) ||
                function.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION),
            invocationKind = returnShape.invocationKind,
            adaptsSpringDataPage = returnShape.adaptsSpringDataPage,
            summary = DocumentationSummaryParser.parse(function.docString).summary
        )
    }

    private fun validateDeclaration(site: QuerySite): Boolean {
        val function = site.function
        if (!function.isPublic()) return fail(site, "Query '${site.identity}' must be public.")
        // Only actual Kotlin query candidates reach this point; unrelated private companions are not artifacts.
        if (site.readModel.origin != Origin.JAVA && function.parentDeclaration?.isPublic() != true) {
            logger.error(
                ArcDiagnostic.QUERY_DECLARATION,
                "Query '${site.identity}' must be declared in a public companion object; make the companion public.",
                function
            )
            return false
        }
        if (Modifier.ABSTRACT in function.modifiers) return fail(site, "Query '${site.identity}' must not be abstract.")
        if (function.extensionReceiver != null) {
            return fail(site, "Query '${site.identity}' must not be an extension function.")
        }
        if (function.typeParameters.isNotEmpty()) {
            logGenericQueryFunction(site)
            return false
        }
        return true
    }

    private fun fail(site: QuerySite, message: String): Boolean {
        logger.error(message, site.function)
        return false
    }

    private fun validateDefaultedParameters(site: QuerySite, parameters: List<QueryParameterModel>): Boolean {
        val defaultedClientParameters = parameters.filter(QueryParameterModel::hasDefault)
        if (defaultedClientParameters.size <= MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS) return true
        logger.error(
            ArcDiagnostic.QUERY_DEFAULT,
            "Kotlin query parameter defaults on '${site.identity}' are unsupported because " +
                "${defaultedClientParameters.size} defaulted client parameters require more than the maximum " +
                "$MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS (${1 shl MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS} invocation branches).",
            site.function
        )
        return false
    }

    private fun validateHostAdapters(
        site: QuerySite,
        parameters: List<QueryParameterModel>,
        returnShape: QueryReturnShape
    ): Boolean {
        val usesHostAdapters = parameters.any { it.source == QueryParameterSource.HOST_ADAPTER }
        if (!usesHostAdapters || returnShape.adaptsSpringDataPage) return true
        logger.error(
            ArcDiagnostic.HOST_ADAPTER_PARAMETER,
            "Query '${site.identity}' uses Pageable or Sort host adapters but does not return exact " +
                "'$SPRING_PAGE_TYPE<T>'; provider-owned adapters require a Page return to prevent Arc from " +
                "sorting or paging the result again.",
            site.function
        )
        return false
    }

    private fun resolveTransport(site: QuerySite, returnShape: QueryReturnShape): String? {
        val declaredTransport = site.function.enumArgument(QUERY_TRANSPORT_ANNOTATION, "value")
        if (declaredTransport == "OBSERVABLE" && !returnShape.isObservable) {
            logger.error("Query '${site.identity}' declares observable transport but does not return Flow<T>, Flow.Publisher<T>, or an RxJava 3 ObservableSource<T>.", site.function)
            return null
        }
        if (declaredTransport == "REQUEST_RESPONSE" && returnShape.isObservable) {
            logger.error("Query '${site.identity}' declares request-response transport but returns an observable type.", site.function)
            return null
        }
        return if (returnShape.isObservable) "OBSERVABLE" else "REQUEST_RESPONSE"
    }

    private fun resolveExplicitPath(site: QuerySite): Pair<Boolean, String?> {
        val classPaths = site.readModel.annotationsNamed(PATH_ANNOTATION).mapNotNull { it.stringArgument("value") }.distinct().toList()
        val methodPaths = site.function.annotationsNamed(PATH_ANNOTATION).mapNotNull { it.stringArgument("value") }.distinct().toList()
        if (classPaths.size > 1 || methodPaths.size > 1) {
            logger.error("Query '${site.identity}' has conflicting ambiguous @Path declarations.", site.function)
            return false to null
        }
        return true to (methodPaths.singleOrNull() ?: classPaths.singleOrNull())
    }

    private fun logGenericQueryFunction(site: QuerySite) {
        val typeParameterBounds = site.function.typeParameters.asSequence()
            .flatMap { typeParameter -> typeParameter.bounds }
            .map { bound -> bound.resolve() }
            .toList()
        val infrastructureBound = typeParameterBounds.firstNotNullOfOrNull { bound ->
            matchingQueryBound(bound, QUERY_INFRASTRUCTURE_TYPES.keys, QUERY_INFRASTRUCTURE_SIMPLE_NAMES)
        }
        val hostAdapterBound = typeParameterBounds.firstNotNullOfOrNull { bound ->
            matchingQueryBound(bound, QUERY_HOST_ADAPTER_TYPES.keys, QUERY_HOST_ADAPTER_SIMPLE_NAMES)
        }
        when {
            infrastructureBound != null -> logger.error(
                ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER,
                "Query '${site.identity}' must not use a generic infrastructure parameter bounded by " +
                    "'$infrastructureBound'; only exact non-generic query infrastructure parameter types are supported.",
                site.function
            )
            hostAdapterBound != null -> logger.error(
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query '${site.identity}' must not use a generic host adapter parameter bounded by '$hostAdapterBound'; " +
                    "only exact non-generic '$SPRING_PAGEABLE_TYPE' and '$SPRING_SORT_TYPE' parameters are supported.",
                site.function
            )
            else -> logger.error("Query '${site.identity}' must not declare type parameters.", site.function)
        }
    }

    private fun matchingQueryBound(bound: KSType, exactTypes: Set<String>, simpleNames: Set<String>): String? {
        val declaration = bound.declaration as? KSClassDeclaration ?: return null
        val qualifiedName = declaration.qualifiedName?.asString() ?: return null
        return qualifiedName.takeIf {
            qualifiedName in exactTypes ||
                declaration.simpleName.asString() in simpleNames ||
                declaration.getAllSuperTypes().any { supertype ->
                    supertype.declaration.qualifiedName?.asString() in exactTypes
                }
        }
    }
}
