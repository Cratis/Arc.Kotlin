// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance

/** The command method (`handle` or `provide`) being read. */
internal class CommandMethodSite(
    val commandName: String,
    val method: KSFunctionDeclaration,
    val methodName: String
) {
    val label: String get() = "$commandName.$methodName"
}

private class ParameterSite(val owner: CommandMethodSite, val parameter: KSValueParameter, val name: String) {
    val label: String get() = "'$name' on '${owner.label}'"
}

private class ResolvedParameter(
    val typeName: String,
    val resolution: CommandParameterResolution,
    val isJavaOptional: Boolean
)

private class ResolvedReturn(val type: KSType, val name: String)

/** Validates the parameters and return type of command handler and provide methods. */
internal class CommandMethodReader(private val logger: ArcDiagnosticReporter) {
    fun parameters(site: CommandMethodSite): List<HandlerParameterModel>? {
        val parameters = mutableListOf<HandlerParameterModel>()
        site.method.parameters.forEach { parameter ->
            parameters.add(parameter(site, parameter, parameters.size) ?: return null)
        }
        return parameters
    }

    fun invocationKind(site: CommandMethodSite, resolver: Resolver, allowNullableReturn: Boolean = true): InvocationKind? {
        val returned = resolveReturn(site, allowNullableReturn) ?: return null
        if (isCompletionStage(returned.type, resolver)) return completionStageKind(site, returned.type, allowNullableReturn)
        return if (returned.name in VOID_TYPE_NAMES) InvocationKind.UNIT else InvocationKind.VALUE
    }

    private fun fail(node: KSNode, message: String): Nothing? {
        logger.error(message, node)
        return null
    }

    private fun parameter(site: CommandMethodSite, parameter: KSValueParameter, index: Int): HandlerParameterModel? {
        val type = parameter.type.resolve()
        val parameterName = parameter.name?.asString() ?: "argument$index"
        val parameterSite = ParameterSite(site, parameter, parameterName)
        if (rejectMap(type, parameterSite)) return null
        val resolved = resolveParameterType(type, parameterSite) ?: return null
        if (!validateResolved(type, resolved, parameterSite)) return null
        return HandlerParameterModel(parameterName, renderClassLiteralType(resolved.typeName), resolved.resolution)
    }

    private fun resolveParameterType(type: KSType, site: ParameterSite): ResolvedParameter? {
        val declaration = type.declaration as? KSClassDeclaration
        val qualifiedName = declaration?.qualifiedName?.asString()
        if (type.isError || declaration == null || qualifiedName == null) {
            return fail(
                site.parameter,
                "Parameter '${site.name}' on '${site.owner.label}' must have a resolvable class or interface type."
            )
        }
        val isJavaOptional = qualifiedName == JAVA_OPTIONAL_TYPE
        if (isJavaOptional && !validateOptionalOrigin(site)) return null
        val resolved = if (isJavaOptional) {
            resolveJavaOptional(type, site)
        } else {
            resolveService(type, declaration, qualifiedName, site)
        } ?: return null
        return ResolvedParameter(resolved.first, resolved.second, isJavaOptional)
    }

    private fun rejectMap(type: KSType, site: ParameterSite): Boolean {
        if (!containsMapType(type)) return false
        logger.error(
            ArcDiagnostic.PROXY_SHAPE,
            "Service parameter '${site.owner.label}.${site.name}' value path 'parameter' uses a map; maps are supported only for artifact properties.",
            site.parameter
        )
        return true
    }

    private fun validateOptionalOrigin(site: ParameterSite): Boolean {
        if (site.owner.method.origin == Origin.JAVA) return true
        fail(
            site.parameter,
            "Parameter ${site.label} must not use java.util.Optional from Kotlin; " +
                "use a nullable @ReadModel parameter or an ordinary non-null service parameter."
        )
        return false
    }

    private fun validateResolved(type: KSType, resolved: ResolvedParameter, site: ParameterSite): Boolean {
        if (site.parameter.hasDefault) {
            logger.warn(
                ArcDiagnostic.INTEROP,
                "Parameter ${site.label} has a default value, but generated " +
                    "Java/Kotlin invocation always resolves and supplies the argument.",
                site.parameter
            )
        }
        if (type.arguments.isNotEmpty() && !resolved.isJavaOptional) {
            fail(site.parameter, "Parameter ${site.label} must not use a parameterized service type.")
            return false
        }
        if (resolved.typeName == "kotlin.Array") {
            fail(site.parameter, "Parameter ${site.label} must not use an array service type.")
            return false
        }
        return true
    }

    private fun resolveJavaOptional(type: KSType, site: ParameterSite): Pair<String, CommandParameterResolution>? {
        val typeArgument = type.arguments.singleOrNull()
        val argument = typeArgument?.type?.resolve()
        val argumentName = (argument?.declaration as? KSClassDeclaration)?.qualifiedName?.asString()
        if (argumentName == null || isInvalidOptional(type, typeArgument.variance, argument)) {
            return fail(
                site.parameter,
                "Java Optional parameter ${site.label} must have one concrete, " +
                    "invariant, non-null, non-parameterized class or interface type."
            )
        }
        return argumentName to CommandParameterResolution.OPTIONAL
    }

    private fun isInvalidOptional(type: KSType, variance: Variance?, argument: KSType?): Boolean =
        type.nullability == Nullability.NULLABLE || variance != Variance.INVARIANT ||
            argument == null || argument.isError ||
            argument.nullability == Nullability.NULLABLE || argument.arguments.isNotEmpty()

    private fun resolveService(
        type: KSType,
        declaration: KSClassDeclaration,
        qualifiedName: String,
        site: ParameterSite
    ): Pair<String, CommandParameterResolution>? {
        val nullable = type.nullability == Nullability.NULLABLE
        if (nullable && !declaration.hasAnnotation(READ_MODEL_ANNOTATION)) {
            return fail(
                site.parameter,
                "Parameter ${site.label} may be nullable only when its type is an @ReadModel command dependency."
            )
        }
        return qualifiedName to if (nullable) CommandParameterResolution.NULLABLE else CommandParameterResolution.REQUIRED
    }

    private fun resolveReturn(site: CommandMethodSite, allowNullableReturn: Boolean): ResolvedReturn? {
        val returnReference = site.method.returnType
            ?: return fail(site.method, "Command method '${site.label}' has no resolvable return type.")
        val returnType = returnReference.resolve()
        val returnName = returnType.declaration.qualifiedName?.asString()
        if (returnName == null || isUnresolvable(returnType)) {
            return fail(site.method, "Command method '${site.label}' has an unsupported or unresolvable return type.")
        }
        if (returnName == "kotlin.Nothing") {
            return fail(site.method, "Command method '${site.label}' must return a value, Unit, void, or CompletionStage<T>.")
        }
        if (!allowNullableReturn && returnType.nullability == Nullability.NULLABLE) {
            return fail(site.method, "Command method '${site.label}' must not declare a nullable return type.")
        }
        return ResolvedReturn(returnType, returnName)
    }

    private fun completionStageKind(
        site: CommandMethodSite,
        returnType: KSType,
        allowNullableReturn: Boolean
    ): InvocationKind? {
        if (returnType.arguments.size != 1 || returnType.arguments.single().type == null) {
            return fail(site.method, "Command method '${site.label}' must return CompletionStage<T> with a concrete T.")
        }
        val result = resolveCompletionResult(site, returnType.arguments.single().type!!.resolve(), allowNullableReturn)
            ?: return null
        return if (result.name in VOID_TYPE_NAMES) InvocationKind.COMPLETION_STAGE_VOID else InvocationKind.COMPLETION_STAGE
    }

    private fun resolveCompletionResult(
        site: CommandMethodSite,
        resultType: KSType,
        allowNullableReturn: Boolean
    ): ResolvedReturn? {
        val resultName = resultType.declaration.qualifiedName?.asString()
        if (resultName == null || isUnresolvable(resultType)) {
            return fail(site.method, "Command method '${site.label}' has an unresolvable CompletionStage result type.")
        }
        if (!allowNullableReturn && resultType.nullability == Nullability.NULLABLE) {
            return fail(site.method, "Command method '${site.label}' must not return CompletionStage<T?>.")
        }
        return ResolvedReturn(resultType, resultName)
    }

    private fun isUnresolvable(type: KSType): Boolean = type.isError || type.declaration is KSTypeParameter
}
