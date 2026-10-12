// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

/** Discovers and validates `@HandlesCommandResponseValues` handlers, local and imported from dependencies. */
internal class ResponseHandlerRegistry(
    private val logger: ArcDiagnosticReporter,
    private val graphLogger: ArcDiagnosticReporter,
    private val importedHandlers: List<ResponseHandlerMetadata.Declaration>
) {
    private val handlerNames = sortedSetOf<String>()
    private val contributions = mutableMapOf<String, List<String>>()
    private val handledTypes = sortedSetOf<String>()
    private val exported = sortedMapOf<String, List<String>>()

    /** The response value types handled by a declarative handler. */
    val declarativeHandledTypes: Set<String> get() = handledTypes

    /** The handlers, with their handled types, that this compilation exports for dependent modules. */
    val exportedHandlers: Map<String, List<String>> get() = exported

    /** True when a handler could not be resolved yet and a later round must retry. */
    var hasDeferredInputs = false
        private set

    fun discover(resolver: Resolver): List<KSAnnotated> {
        hasDeferredInputs = false
        // Only stable names cross rounds. Revalidate binary annotations and SPI through this resolver.
        contributions.clear()
        exported.clear()
        importedHandlers.forEach { imported -> revalidateImported(resolver, imported) }
        val discovered = resolver.getSymbolsWithAnnotation(HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION, inDepth = true)
            .toList()
        handlerNames += discovered.filterIsInstance<KSClassDeclaration>()
            .filter { it.containingFile != null }.mapNotNull { it.qualifiedName?.asString() }
        val symbols = handlerNames.mapNotNull { name ->
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(name)).also {
                if (it == null) hasDeferredInputs = true
            }
        } + discovered.filter { it !is KSClassDeclaration || it.qualifiedName == null }
        val deferred = symbols.filterNot(KSAnnotated::validateForProcessing).toMutableList()
        symbols.filter(KSAnnotated::validateForProcessing)
            .filterIsInstance<KSClassDeclaration>()
            .sortedBy { declaration -> declaration.qualifiedName?.asString().orEmpty() }
            .forEach { declaration -> register(declaration, deferred) }
        handledTypes.clear()
        handledTypes += contributions.values.flatten()
        return deferred
    }

    private fun handledValueTypes(declaration: KSClassDeclaration): List<KSType> =
        declaration.annotationsNamed(HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION)
            .flatMap { annotation -> annotation.argumentValues("value").asSequence() }
            .mapNotNull { value -> value as? KSType }
            .toList()

    private fun invalidImported(imported: ResponseHandlerMetadata.Declaration, reason: String) {
        graphLogger.error(
            ArcDiagnostic.COMMAND_HANDLER,
            "Dependency handler '${imported.handler}' in ${imported.resource} $reason; rebuild the producer " +
                "and dependency handler index."
        )
    }

    private fun revalidateImported(resolver: Resolver, imported: ResponseHandlerMetadata.Declaration) {
        val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(imported.handler))
        if (declaration == null) {
            invalidImported(imported, "cannot be resolved on the compilation classpath")
            return
        }
        val values = handledValueTypes(declaration)
        if (!declaration.validateForProcessing() || values.any(KSType::isError)) {
            hasDeferredInputs = true
            invalidImported(imported, "has unresolved annotation or SPI types")
            return
        }
        if (!importedAgrees(declaration, values, imported)) {
            invalidImported(imported, "does not agree with its public top-level annotation and supported handler SPI")
            return
        }
        contributions[imported.handler] = imported.values
    }

    private fun importedAgrees(
        declaration: KSClassDeclaration,
        values: List<KSType>,
        imported: ResponseHandlerMetadata.Declaration
    ): Boolean = declaration.isPublic() && declaration.parentDeclaration == null &&
        isSupportedHandler(declaration) && values.all(::isExportableResponseType) &&
        values.mapNotNull { it.declaration.qualifiedName?.asString() }.distinct().sorted() == imported.values

    private fun register(declaration: KSClassDeclaration, deferred: MutableList<KSAnnotated>) {
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null) {
            logError("@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME handlers must not be local.", declaration)
            return
        }
        val handledTypeSymbols = handledValueTypes(declaration)
        if (handledTypeSymbols.any(KSType::isError)) {
            deferred.add(declaration)
            return
        }
        if (!isSupportedHandler(declaration)) {
            logError(
                "@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME declaration '$qualifiedName' must implement " +
                    "CommandResponseValueHandler, BlockingCommandResponseValueHandler, or " +
                    "AsyncCommandResponseValueHandler.",
                declaration
            )
            return
        }
        val handled = handledTypeSymbols.mapNotNull { type -> type.declaration.qualifiedName?.asString() }
        if (handled.isEmpty()) {
            logError(
                "@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME declaration '$qualifiedName' must declare " +
                    "at least one handled response value type.",
                declaration
            )
            return
        }
        if (conflictsWithImported(declaration, qualifiedName, handled)) return
        contributions[qualifiedName] = handled
        if (isExportableHandler(declaration, qualifiedName, handledTypeSymbols)) {
            exported[qualifiedName] = handled.distinct().sorted()
        }
    }

    private fun logError(message: String, declaration: KSClassDeclaration) {
        logger.error(ArcDiagnostic.COMMAND_HANDLER, message, declaration)
    }

    private fun conflictsWithImported(declaration: KSClassDeclaration, qualifiedName: String, handled: List<String>): Boolean {
        val imported = importedHandlers.firstOrNull { it.handler == qualifiedName }
        if (imported == null || imported.values == handled.distinct().sorted()) return false
        graphLogger.error(
            ArcDiagnostic.COMMAND_HANDLER,
            "Handler '$qualifiedName' conflicts with ${imported.resource}; remove the conflicting declaration.",
            declaration
        )
        return true
    }

    private fun isExportableHandler(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        handledTypeSymbols: List<KSType>
    ): Boolean = declaration.containingFile != null && declaration.isPublic() && declaration.parentDeclaration == null &&
        ResponseHandlerMetadata.exportableName(qualifiedName) && handledTypeSymbols.all(::isExportableResponseType)

    private fun isExportableResponseType(value: KSType): Boolean {
        val type = value.declaration as? KSClassDeclaration ?: return false
        return type.isPublic() && type.parentDeclaration == null &&
            type.qualifiedName?.asString()?.let(ResponseHandlerMetadata::exportableName) == true
    }

    private fun isSupportedHandler(declaration: KSClassDeclaration): Boolean =
        declaration.getAllSuperTypes()
            .mapNotNull { superType -> superType.declaration.qualifiedName?.asString() }
            .any(SUPPORTED_RESPONSE_VALUE_HANDLER_TYPES::contains)
}
