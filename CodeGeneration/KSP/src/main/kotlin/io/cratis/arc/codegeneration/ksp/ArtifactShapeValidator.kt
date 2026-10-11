// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier

/** Validates the declaration shape of commands, read models and exported types. */
internal class ArtifactShapeValidator(private val logger: ArcDiagnosticReporter) {
    fun validateCommandClass(command: KSClassDeclaration, qualifiedName: String): Boolean =
        ensure(command.parentDeclaration == null, command,
            "Command '$qualifiedName' must be a top-level class; nested commands are not supported.") &&
            ensure(command.classKind == ClassKind.CLASS, command, "Command '$qualifiedName' must be a concrete class.") &&
            ensure(command.isPublic(), command,
                "Command '$qualifiedName' must be public so its generated handler can invoke it.") &&
            ensure(Modifier.ABSTRACT !in command.modifiers, command, "Command '$qualifiedName' must not be abstract.") &&
            ensure(command.typeParameters.isEmpty(), command, "Command '$qualifiedName' must not declare type parameters.")

    fun findCommandHandler(command: KSClassDeclaration, qualifiedName: String): KSFunctionDeclaration? {
        val namedHandlers = command.getDeclaredFunctions().filter { it.simpleName.asString() == HANDLER_NAME }.toList()
        if (namedHandlers.isEmpty()) {
            logger.error("Command '$qualifiedName' must declare exactly one public instance function named '$HANDLER_NAME'.", command)
            return null
        }
        if (namedHandlers.size > 1) {
            logger.error("Command '$qualifiedName' has overloaded '$HANDLER_NAME' functions; exactly one is supported.", command)
            return null
        }
        val handler = namedHandlers.single()
        return handler.takeIf { validateHandler(it, qualifiedName) }
    }

    private fun validateHandler(handler: KSFunctionDeclaration, qualifiedName: String): Boolean {
        val label = "Handler '$qualifiedName.$HANDLER_NAME'"
        return ensure(handler.isPublic(), handler, "$label must be public.") &&
            ensure(isInstanceFunction(handler), handler, "$label must be a non-static instance function.") &&
            ensure(Modifier.ABSTRACT !in handler.modifiers, handler, "$label must not be abstract.") &&
            ensure(handler.extensionReceiver == null, handler, "$label must not be an extension function.") &&
            ensure(handler.typeParameters.isEmpty(), handler, "$label must not declare type parameters.")
    }

    fun validateProvideMethod(method: KSFunctionDeclaration, qualifiedName: String): Boolean {
        val label = "Provide method '$qualifiedName.$PROVIDE_NAME'"
        val isConcrete = Modifier.ABSTRACT !in method.modifiers && method.extensionReceiver == null &&
            method.typeParameters.isEmpty()
        return ensure(method.isPublic(), method, "$label must be public.") &&
            ensure(isInstanceFunction(method), method, "$label must be a non-static instance function.") &&
            ensure(isConcrete, method, "$label must be concrete, non-generic, and not an extension function.")
    }

    fun validateReadModelShape(readModel: KSClassDeclaration, qualifiedName: String): Boolean =
        ensure(readModel.parentDeclaration == null, readModel,
            "Read model '$qualifiedName' must be a top-level class; nested read models are not supported.") &&
            ensure(readModel.classKind == ClassKind.CLASS, readModel, "Read model '$qualifiedName' must be a concrete class.") &&
            ensure(readModel.isPublic(), readModel,
                "Read model '$qualifiedName' must be public so generated performers can use it.") &&
            ensure(Modifier.ABSTRACT !in readModel.modifiers, readModel, "Read model '$qualifiedName' must not be abstract.") &&
            ensure(readModel.typeParameters.isEmpty(), readModel,
                "Read model '$qualifiedName' must not declare type parameters.")

    fun validateExportedTypeShape(declaration: KSClassDeclaration, qualifiedName: String): Boolean {
        val kind = declaration.classKind
        return ensureExported(declaration.isPublic(), declaration,
            "Exported type '$qualifiedName' must be public so generated clients can use it.") &&
            ensureExported(declaration.typeParameters.isEmpty(), declaration,
                "Exported type '$qualifiedName' must not declare type parameters; " +
                    "a generic definition has no single shape to generate.") &&
            ensureExported(kind == ClassKind.CLASS || kind == ClassKind.ENUM_CLASS || kind == ClassKind.INTERFACE, declaration,
                "Exported type '$qualifiedName' must be a class, enum class or interface.") &&
            ensureExported(kind != ClassKind.CLASS || Modifier.ABSTRACT !in declaration.modifiers, declaration,
                "Exported type '$qualifiedName' must not be abstract; " +
                    "export the concrete derived types instead.")
    }

    private fun isInstanceFunction(function: KSFunctionDeclaration): Boolean =
        function.functionKind == FunctionKind.MEMBER && Modifier.JAVA_STATIC !in function.modifiers

    private fun ensure(passes: Boolean, node: KSNode, message: String): Boolean {
        if (!passes) logger.error(message, node)
        return passes
    }

    private fun ensureExported(passes: Boolean, node: KSNode, message: String): Boolean {
        if (!passes) logger.error(ArcDiagnostic.EXPORTED_TYPE_TARGET, message, node)
        return passes
    }
}
