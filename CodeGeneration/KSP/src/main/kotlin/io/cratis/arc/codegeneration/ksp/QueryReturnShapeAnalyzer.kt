// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance

/** The invocation and result characteristics of a query return type. */
internal data class QueryReturnShape(
    val invocationKind: QueryInvocationKind,
    val isEnumerable: Boolean,
    val supportsPaging: Boolean,
    val isObservable: Boolean,
    val adaptsSpringDataPage: Boolean
)

private class UnwrappedQueryReturn(
    val type: KSType,
    val invocationKind: QueryInvocationKind,
    val isObservable: Boolean
)

/** Validates the declared return type of one query function and classifies its shape. */
internal class QueryReturnShapeAnalyzer(
    private val logger: ArcDiagnosticReporter,
    private val resolver: Resolver,
    private val readModel: KSClassDeclaration,
    private val function: KSFunctionDeclaration
) {
    private val identity = "${requireNotNull(readModel.qualifiedName).asString()}.${function.simpleName.asString()}"

    fun analyze(): QueryReturnShape? {
        val declaredType = resolveDeclaredType() ?: return null
        val unwrapped = unwrap(declaredType) ?: return null
        val returnType = unwrapped.type
        val returnName = validateReturnName(returnType) ?: return null
        if (!validateSpringDataPageReturn(returnType, returnName, unwrapped.isObservable)) return null

        val readModelName = requireNotNull(readModel.qualifiedName).asString()
        if (returnName == readModelName) {
            return QueryReturnShape(unwrapped.invocationKind, false, false, unwrapped.isObservable, false)
        }
        return classifyCollection(unwrapped, returnName, readModelName)
    }

    private fun fail(message: String): QueryReturnShape? {
        logger.error(message, function)
        return null
    }

    private fun resolveDeclaredType(): KSType? {
        val declaredType = function.returnType?.resolve()
        if (declaredType == null || declaredType.isError) {
            logger.error("Query '$identity' has no resolvable return type.", function)
            return null
        }
        if (containsMapType(declaredType)) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Query '$identity' return value path 'return' uses a map; query and observable return maps are unsupported.",
                function
            )
            return null
        }
        return declaredType
    }

    private fun classifyCollection(
        unwrapped: UnwrappedQueryReturn,
        returnName: String,
        readModelName: String
    ): QueryReturnShape? {
        val returnType = unwrapped.type
        val isSpringDataPage = returnName == SPRING_PAGE_TYPE
        val isEnumerable = returnName in COLLECTION_TYPE_NAMES || returnName == "kotlin.Array"
        val isPage = returnName == QUERY_PAGE_TYPE || isSpringDataPage
        if (!isEnumerable && !isPage) {
            val detail = if (returnType.arguments.isNotEmpty() || returnName in MAP_TYPE_NAMES) {
                "unsupported generic or map return shape"
            } else {
                "return model '${returnName}' does not match annotated read model '$readModelName'"
            }
            return fail("Query '$identity' has $detail.")
        }
        if (!validateReturnElementType(returnType, readModelName, isSpringDataPage)) return null
        return QueryReturnShape(unwrapped.invocationKind, true, isPage, unwrapped.isObservable, isSpringDataPage)
    }

    private fun unwrap(returnType: KSType): UnwrappedQueryReturn? {
        val outerReturnName = returnType.declaration.qualifiedName?.asString()
        if (isObservableType(outerReturnName)) {
            return unwrapObservable(returnType, outerReturnName)
        }
        if (isCompletionStage(returnType, resolver)) return unwrapCompletionStage(returnType)
        return UnwrappedQueryReturn(returnType, QueryInvocationKind.DIRECT, false)
    }

    private fun isObservableType(typeName: String?): Boolean =
        typeName == KOTLIN_FLOW_TYPE || typeName in JDK_PUBLISHER_TYPES || typeName in RXJAVA3_OBSERVABLE_TYPES

    private fun isUnusableReturnName(returnName: String?, returnType: KSType): Boolean =
        returnName == null || returnType.declaration is KSTypeParameter || returnName in VOID_TYPE_NAMES

    private fun isUnsupportedPublisher(returnName: String): Boolean =
        returnName in UNSUPPORTED_STREAM_TYPES || returnName.endsWith(".Publisher")

    private fun isNullableDeclaration(returnType: KSType): Boolean =
        returnType.nullability == Nullability.NULLABLE || function.hasNullableAnnotation()

    private fun hasSingleConcreteArgument(type: KSType): Boolean =
        type.arguments.size == 1 && type.arguments.single().type != null

    private fun unwrapObservable(returnType: KSType, outerReturnName: String?): UnwrappedQueryReturn? {
        if (!hasSingleConcreteArgument(returnType)) {
            logger.error("Query '$identity' must return an observable type with a concrete model shape.", function)
            return null
        }
        val modelType = returnType.arguments.single().type!!.resolve()
        if (modelType.isError || modelType.declaration is KSTypeParameter) {
            logger.error("Query '$identity' has an unresolvable observable model shape.", function)
            return null
        }
        if (modelType.nullability == Nullability.NULLABLE) {
            logger.error("Query '$identity' must not return an observable nullable model shape.", function)
            return null
        }
        val invocationKind = when {
            outerReturnName == KOTLIN_FLOW_TYPE -> QueryInvocationKind.FLOW
            outerReturnName in RXJAVA3_OBSERVABLE_TYPES -> QueryInvocationKind.RX_OBSERVABLE
            else -> QueryInvocationKind.JDK_PUBLISHER
        }
        return UnwrappedQueryReturn(modelType, invocationKind, true)
    }

    private fun unwrapCompletionStage(returnType: KSType): UnwrappedQueryReturn? {
        if (function.origin != Origin.JAVA) {
            logger.error("Query '$identity' may use CompletionStage only when declared in Java.", function)
            return null
        }
        if (!hasSingleConcreteArgument(returnType)) {
            logger.error("Java query '$identity' must return CompletionStage<T> with a concrete T.", function)
            return null
        }
        val modelType = returnType.arguments.single().type!!.resolve()
        if (modelType.nullability == Nullability.NULLABLE) {
            logger.error("Java query '$identity' must not return CompletionStage<T?>.", function)
            return null
        }
        return UnwrappedQueryReturn(modelType, QueryInvocationKind.COMPLETION_STAGE, false)
    }

    private fun validateReturnName(returnType: KSType): String? {
        val returnName = returnType.declaration.qualifiedName?.asString()
        if (returnName == null || isUnusableReturnName(returnName, returnType)) {
            logger.error("Query '$identity' must return its read model, a supported collection, array, QueryPage, or exact Spring Data Page.", function)
            return null
        }
        if (isUnsupportedPublisher(returnName)) {
            logger.error("Query '$identity' returns an unsupported observable publisher type.", function)
            return null
        }
        return returnName
    }

    private fun hostAdapterFailure(message: String): Boolean {
        logger.error(ArcDiagnostic.HOST_ADAPTER_PARAMETER, message, function)
        return false
    }

    private fun validateSpringDataPageReturn(returnType: KSType, returnName: String, isObservable: Boolean): Boolean {
        val returnDeclaration = returnType.declaration as? KSClassDeclaration
        val springPageSupertype = returnDeclaration?.getAllSuperTypes()
            ?.mapNotNull { supertype -> supertype.declaration.qualifiedName?.asString() }
            ?.firstOrNull { supertype -> supertype == SPRING_PAGE_TYPE }
        val isSpringDataPage = returnName == SPRING_PAGE_TYPE
        if (!isSpringDataPage && springPageSupertype != null) {
            return hostAdapterFailure(
                "Query '$identity' returns Spring Data Page subtype '$returnName'; only exact non-null " +
                    "'$SPRING_PAGE_TYPE<T>' returns are supported."
            )
        }
        if (isSpringDataPage && isObservable) {
            return hostAdapterFailure(
                "Observable query '$identity' must not return '$SPRING_PAGE_TYPE<T>'; Spring Data pages are supported " +
                    "only for direct, suspending, and Java CompletionStage queries."
            )
        }
        if (isSpringDataPage && isNullableDeclaration(returnType)) {
            return hostAdapterFailure("Query '$identity' must return the exact non-null type '$SPRING_PAGE_TYPE<T>'.")
        }
        return true
    }

    private fun validateReturnElementType(
        returnType: KSType,
        readModelName: String,
        isSpringDataPage: Boolean
    ): Boolean {
        val diagnostic = if (isSpringDataPage) ArcDiagnostic.HOST_ADAPTER_PARAMETER else ArcDiagnostic.QUERY_RETURN
        if (!hasSingleConcreteArgument(returnType)) {
            logger.error(
                diagnostic,
                "Query '$identity' must return a supported shape with the concrete read model element type.",
                function
            )
            return false
        }
        if (isSpringDataPage && returnType.arguments.single().variance != Variance.INVARIANT) {
            return hostAdapterFailure(
                "Query '$identity' must return exact invariant '$SPRING_PAGE_TYPE<T>'; wildcard, projected, and star " +
                    "page element types are unsupported."
            )
        }
        return validateElementMatchesReadModel(returnType.arguments.single().type!!.resolve(), readModelName, diagnostic)
    }

    private fun validateElementMatchesReadModel(
        elementType: KSType,
        readModelName: String,
        diagnostic: ArcDiagnostic
    ): Boolean {
        val elementName = elementType.declaration.qualifiedName?.asString()
        if (elementType.isError || elementType.declaration is KSTypeParameter || elementName == null) {
            logger.error(diagnostic, "Query '$identity' has an unresolvable return model element type.", function)
            return false
        }
        if (elementName == readModelName) return true
        val nested = elementType.arguments.isNotEmpty() || elementType.declaration.parentDeclaration != null
        val detail = if (nested) "nested return models are not supported" else
            "return model '$elementName' does not match annotated read model '$readModelName'"
        logger.error(diagnostic, "Query '$identity': $detail.", function)
        return false
    }
}
