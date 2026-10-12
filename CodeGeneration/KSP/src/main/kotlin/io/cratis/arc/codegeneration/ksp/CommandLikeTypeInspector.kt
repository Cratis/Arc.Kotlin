// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

/** Warns about types that look like commands without `@Command`, and about external handlers of commands. */
internal class CommandLikeTypeInspector(
    private val logger: ArcDiagnosticReporter,
    private val inspected: MutableSet<String>
) {
    private val helperSuffixes = listOf("Extensions", "Helper", "Helpers")

    fun inspect(resolver: Resolver) {
        resolver.getAllFiles().flatMap { file -> file.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration -> inspect(declaration) }
    }

    private fun inspect(declaration: KSClassDeclaration) {
        val qualifiedName = declaration.qualifiedName?.asString() ?: return
        if (!inspected.add(qualifiedName) || !isCandidate(declaration)) return
        val handles = declaration.getDeclaredFunctions()
            .filter { function -> function.simpleName.asString() == HANDLER_NAME }
            .toList()
        warnWhenMissingCommand(declaration, qualifiedName, handles)
        reportExternalHandlers(qualifiedName, handles)
    }

    private fun isCandidate(declaration: KSClassDeclaration): Boolean {
        if (declaration.hasAnnotation(COMMAND_ANNOTATION)) return false
        if (declaration.classKind != ClassKind.CLASS || declaration.parentDeclaration != null) return false
        val simpleName = declaration.simpleName.asString()
        return helperSuffixes.none(simpleName::endsWith)
    }

    private fun isPublicInstanceFunction(function: KSFunctionDeclaration): Boolean =
        function.functionKind == FunctionKind.MEMBER && Modifier.JAVA_STATIC !in function.modifiers && function.isPublic()

    private fun warnWhenMissingCommand(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        handles: List<KSFunctionDeclaration>
    ) {
        val commandLikeHandle = handles.firstOrNull(::isPublicInstanceFunction)
        val hasPublicState = declaration.getAllProperties().any { property -> property.isPublic() }
        if (commandLikeHandle == null || !hasPublicState) return
        logger.warn(
            ArcDiagnostic.MISSING_COMMAND,
            "Type '$qualifiedName' has a handle function and public state but is missing @Command; " +
                "add @Command or rename the function if this is not a command.",
            declaration
        )
    }

    private fun reportExternalHandlers(qualifiedName: String, handles: List<KSFunctionDeclaration>) {
        handles.filter { function -> isPublicInstanceFunction(function) && acceptsCommand(function) }
            .forEach { function ->
                logger.error(
                    ArcDiagnostic.COMMAND_HANDLER,
                    "External handler '$qualifiedName.${function.simpleName.asString()}' accepts an @Command type; " +
                        "move handling to a public instance handle function on the command.",
                    function
                )
            }
    }

    private fun acceptsCommand(function: KSFunctionDeclaration): Boolean = function.parameters.any { parameter ->
        (parameter.type.resolve().declaration as? KSClassDeclaration)?.hasAnnotation(COMMAND_ANNOTATION) == true
    }
}
