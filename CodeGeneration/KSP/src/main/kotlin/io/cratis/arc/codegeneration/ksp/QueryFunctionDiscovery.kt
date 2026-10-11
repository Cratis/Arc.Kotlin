// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import java.io.File

/** Finds the query functions declared by one read model. */
internal class QueryFunctionDiscovery(
    private val logger: ArcDiagnosticReporter,
    private val resolver: Resolver,
    private val readModel: KSClassDeclaration,
    private val qualifiedName: String
) {
    /** Returns the query functions, or null when the read model declares an invalid query. */
    fun discover(): List<KSFunctionDeclaration>? =
        if (readModel.origin == Origin.JAVA) javaQueryFunctions() else kotlinQueryFunctions()

    private fun kotlinQueryFunctions(): List<KSFunctionDeclaration>? {
        val unsupportedInstanceFunctions = readModel.getDeclaredFunctions()
            .filter { function -> function.origin == Origin.KOTLIN && !function.isGeneratedModelFunction() }
            .toList()
        if (unsupportedInstanceFunctions.isNotEmpty()) {
            unsupportedInstanceFunctions.forEach { function ->
                logger.error(
                    "Query '${qualifiedName}.${function.simpleName.asString()}' must be declared in the read model companion object.",
                    function
                )
            }
            return null
        }

        val companions = readModel.declarations.filterIsInstance<KSClassDeclaration>()
            .filter(KSClassDeclaration::isCompanionObject)
            .toList()
        if (companions.size > 1) {
            logger.error("Read model '$qualifiedName' has ambiguous companion object declarations.", readModel)
            return null
        }
        return companions.singleOrNull()?.getDeclaredFunctions()
            ?.filterNot { function -> function.isGeneratedModelFunction() }
            ?.filter { function -> function.isQueryCandidate() }
            ?.toList()
            .orEmpty()
    }

    private fun javaQueryFunctions(): List<KSFunctionDeclaration>? {
        val recordComponents = javaRecordComponents()
        var valid = true
        val queries = mutableListOf<KSFunctionDeclaration>()
        readModel.getDeclaredFunctions().filterNot { function ->
            function.isGeneratedModelFunction() || function.simpleName.asString() in recordComponents
        }.filter { function ->
            Modifier.JAVA_STATIC !in function.modifiers || function.isQueryCandidate()
        }.forEach { function ->
            if (isValidJavaQuery(function)) queries.add(function) else valid = false
        }
        return queries.takeIf { valid }
    }

    private fun javaRecordComponents(): Set<String> = readModel.containingFile?.filePath
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?.readText()
        ?.let { source -> parseJavaRecordProperties(source, readModel.simpleName.asString()) }
        ?.map(JavaRecordProperty::name)
        .orEmpty()
        .toSet()

    private fun isValidJavaQuery(function: KSFunctionDeclaration): Boolean {
        val methodName = function.simpleName.asString()
        if (Modifier.JAVA_STATIC !in function.modifiers) {
            logger.error("Java query '$qualifiedName.$methodName' must be static.", function)
            return false
        }
        if (!function.isPublic()) {
            logger.error("Java query '$qualifiedName.$methodName' must be public.", function)
            return false
        }
        return true
    }

    private fun KSFunctionDeclaration.isGeneratedModelFunction(): Boolean {
        if (origin == Origin.SYNTHETIC) return true
        val name = simpleName.asString()
        return name.startsWith('<') || name == "equals" || name == "hashCode" || name == "toString" ||
            name == "copy" || name.matches(Regex("component[0-9]+"))
    }

    private fun KSFunctionDeclaration.isQueryCandidate(): Boolean = hasQuerySpecificAnnotation() || returnsReadModelShape()

    private fun KSAnnotated.hasQuerySpecificAnnotation(): Boolean =
        hasAnnotation(PATH_ANNOTATION) ||
            hasAnnotation(QUERY_HTTP_METHOD_ANNOTATION) ||
            hasAnnotation(QUERY_TRANSPORT_ANNOTATION)

    private fun KSFunctionDeclaration.returnsReadModelShape(): Boolean {
        val readModelName = readModel.qualifiedName?.asString() ?: return false
        val declaredType = returnType?.resolve()?.takeUnless { resolved -> resolved.isError } ?: return false
        val type = unwrapWrapper(declaredType) ?: return false
        val returnName = type.declaration.qualifiedName?.asString() ?: return false
        if (returnName == readModelName) return true
        if (!isCollectionOrPage(returnName)) return false
        return type.arguments.singleOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString() == readModelName
    }

    private fun unwrapWrapper(type: KSType): KSType? {
        val outerName = type.declaration.qualifiedName?.asString()
        val isWrapper = outerName == KOTLIN_FLOW_TYPE || outerName in JDK_PUBLISHER_TYPES ||
            outerName in RXJAVA3_OBSERVABLE_TYPES || isCompletionStage(type, resolver)
        if (!isWrapper) return type
        return type.arguments.singleOrNull()?.type?.resolve()
    }

    private fun isCollectionOrPage(returnName: String): Boolean =
        returnName in COLLECTION_TYPE_NAMES || returnName == ARRAY_TYPE ||
            returnName == QUERY_PAGE_TYPE || returnName == SPRING_PAGE_TYPE
}
