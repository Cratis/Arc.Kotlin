// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

/** The authorization metadata declared directly on one class or operation. */
private class AuthorizationScope(val node: KSAnnotated, val allowAnonymous: Boolean) {
    val authorize: List<KSAnnotation> = node.annotationsNamed(AUTHORIZE_ANNOTATION).toList()
    val roles: List<String> = node.roleAnnotations()

    val restricts: Boolean get() = authorize.isNotEmpty() || roles.isNotEmpty()
    val declaresAuthorization: Boolean get() = allowAnonymous || restricts
}

/** Reads and validates the authorization declared on an artifact class and one of its operations. */
internal class AuthorizationReader(private val logger: ArcDiagnosticReporter) {
    fun read(
        declaration: KSClassDeclaration,
        operation: KSFunctionDeclaration,
        identity: String,
        artifactKind: String
    ): AuthorizationModel? {
        val classAllowAnonymous = declaration.hasAnnotation(ALLOW_ANONYMOUS_ANNOTATION)
        val operationAllowAnonymous = operation.hasAnnotation(ALLOW_ANONYMOUS_ANNOTATION)
        if (classAllowAnonymous && operationAllowAnonymous) {
            logger.error(
                ArcDiagnostic.AUTHORIZATION,
                "$artifactKind '$identity' declares @AllowAnonymous on both the class and operation; declare it once.",
                operation
            )
            return null
        }

        val classScope = AuthorizationScope(declaration, classAllowAnonymous)
        val operationScope = AuthorizationScope(operation, operationAllowAnonymous)
        // One declaration that both opens and restricts access contradicts itself and has no defensible reading,
        // so it stays an error. Metadata split across the class and its operation is a different thing entirely:
        // that is the ordinary way to state a default and then override it for one operation.
        if (!isConsistent(classScope, artifactKind, identity) || !isConsistent(operationScope, artifactKind, identity)) {
            return null
        }

        // Operation-level authorization replaces class-level authorization wholesale rather than merging with it,
        // matching Arc .NET's AuthorizationEvaluator.IsAuthorized(MethodInfo): it resolves the method's own metadata
        // first and only falls back to the declaring type when the method declares none. The class therefore states
        // the default for a read model, and any one operation - open or restricted - overrides it.
        return toModel(if (operationScope.declaresAuthorization) operationScope else classScope)
    }

    private fun isConsistent(scope: AuthorizationScope, artifactKind: String, identity: String): Boolean {
        if (!scope.allowAnonymous || !scope.restricts) return true
        logger.error(
            ArcDiagnostic.AUTHORIZATION,
            "$artifactKind '$identity' cannot combine @AllowAnonymous with @Authorize or @Roles on the same declaration.",
            scope.node
        )
        return false
    }

    private fun toModel(scope: AuthorizationScope): AuthorizationModel {
        val roles = (
            scope.authorize.flatMap { annotation -> annotation.stringListArgument("roles") } + scope.roles
            ).distinct()
        val schemes = scope.authorize.flatMap { annotation -> annotation.stringListArgument("schemes") }.distinct()
        return AuthorizationModel(
            allowAnonymous = scope.allowAnonymous,
            policy = scope.authorize.firstNotNullOfOrNull { annotation ->
                annotation.stringArgument("policy")?.takeIf(String::isNotBlank)
            },
            roles = roles,
            schemes = schemes
        )
    }
}
