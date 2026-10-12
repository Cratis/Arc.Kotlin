// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.metadata.ValidationRuleDescriptor
import org.gradle.api.GradleException

/** Everything needed to render one validator class that also validates shared models. */
internal data class SharedValidatorSpec(
    val className: String,
    val base: String,
    val targetType: String,
    val model: String?,
    val properties: List<PropertyDescriptor>,
    val legacy: List<ValidationTarget>,
    val target: ArtifactTarget,
    val query: Boolean = false
)

/** The TypeScript expressions that locate a shared model value, its path and the cycle guard while validating. */
private class SharedEdgeAccess(val access: String, val path: String, val seen: String)

private val numericComparisonRules = setOf("greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual")

/** The rules a generated client can express; the rest remain enforced by the server. */
internal fun List<ValidationRuleDescriptor>.clientRepresentable(): List<ValidationRuleDescriptor> =
    filterNot { rule ->
        // .NET currently extracts CreditCardAttribute, but @cratis/arc 22.7.0 exposes no creditCard RuleBuilder
        // extension or runtime rule. Keep server enforcement and metadata without generating uncompilable proxies.
        rule.ruleName == "creditCard"
    }

/** Renders generated TypeScript validator classes. */
internal class TypeScriptValidatorRenderer(private val shared: SharedValidationGraph) {
    /** Emit each declaration independently to preserve runtime declaration order and feedback multiplicity. */
    fun appendSharedValidator(out: StringBuilder, spec: SharedValidatorSpec) {
        val declarations = spec.model?.let { shared.declarations[it] }.orEmpty()
        out.append("export class ${spec.className} extends ${spec.base}<${spec.targetType}> {\n")
        declarations.forEachIndexed { index, _ ->
            out.append("    private readonly arcRules$index = new ArcSharedValidator<${spec.targetType}>();\n")
        }
        out.append("    constructor() {\n        super();\n")
        spec.legacy.forEach { member -> member.rules.forEach { rule -> out.appendRule("this", member.propertyName, rule) } }
        declarations.forEachIndexed { index, declaration ->
            declaration.members.forEach { member ->
                member.rules.forEach { out.appendRule("this.arcRules$index", member.name, it) }
            }
        }
        out.append("    }\n\n")
        out.appendValidateOpening(spec)
        declarations.forEachIndexed { index, declaration -> out.appendDeclarationValidation(index, declaration) }
        out.append("        const qualified = results.map(result => new ArcSharedResult(result.severity, result.message,\n")
        out.append("            result.members.length === 0 ? (arcPath ? [arcPath] : []) : result.members.map(member => arcPath ? (member.startsWith('[') ? arcPath + member : arcPath + '.' + member) : member), result.state, result.reason, result.reasonDetail));\n")
        spec.properties.sortedBy { it.name }.forEach { property -> out.appendPropertyEdge(spec, property) }
        out.append("        return qualified;\n    }\n}\n\n")
    }

    private fun StringBuilder.appendValidateOpening(spec: SharedValidatorSpec) {
        append("    validate(value: ${spec.targetType}, arcPath = '', arcSeen = new WeakSet<object>()): ArcSharedResult[] {\n")
        append("        if (value == null) return [];\n")
        if (!spec.query) append("        if (arcSeen.has(value)) return [];\n        arcSeen.add(value);\n")
        append("        const results: ArcSharedResult[] = super.validate(value).filter(result => !(")
        val optional = spec.legacy.filter { it.skipWhenUndefined }
        append(if (optional.isEmpty()) "false" else optional.joinToString(" || ") {
            "value.${it.propertyName} === undefined && result.members.includes('${it.propertyName}')"
        })
        append("));\n")
    }

    private fun StringBuilder.appendDeclarationValidation(index: Int, declaration: SharedValidatorDescriptor) {
        val numeric = declaration.members.filter { member -> member.rules.any { it.ruleName in numericComparisonRules } }
        val condition = numeric.joinToString(" || ") { member ->
            val access = "value.${member.name}"
            "($access != null && (!Number.isFinite($access) || Math.abs($access) > Number.MAX_SAFE_INTEGER || ($access !== 0 && Math.abs($access) < 2.2250738585072014e-308)))"
        }
        if (condition.isNotEmpty()) {
            append("        if ($condition) {\n            results.push(new ArcSharedResult(ArcSharedSeverity.Error, 'The value could not be validated.', [], null, 'validatorFailed'));\n        } else {\n")
        }
        append("        results.push(...this.arcRules$index.validate(value));\n")
        if (condition.isNotEmpty()) append("        }\n")
    }

    private fun StringBuilder.appendPropertyEdge(spec: SharedValidatorSpec, property: PropertyDescriptor) {
        if (property.ignoreValidation || !shared.reaches(property.shape)) return
        val path = "(arcPath ? arcPath + '.${escape(property.name)}' : '${escape(property.name)}')"
        val seen = if (spec.query) "arcSeen${sharedAlias(property.name)}" else "arcSeen"
        if (spec.query) append("        const $seen = new WeakSet<object>();\n")
        appendSharedEdge(property.shape, SharedEdgeAccess("value.${lowerCamel(property.name)}", path, seen), spec.target)
    }

    private fun StringBuilder.appendRule(receiver: String, member: String, rule: ValidationRuleDescriptor) {
        append("        $receiver.ruleFor(c => c.$member).${rule.ruleName}(${renderValidationArguments(rule)})")
        rule.message?.let { append(".withMessage(${typescriptString(it)})") }
        append(";\n")
    }

    private fun StringBuilder.appendSharedEdge(shape: TypeShapeDescriptor, edge: SharedEdgeAccess, target: ArtifactTarget) {
        when (shape.kind) {
            TypeShapeKind.VALUE -> {
                val name = requireNotNull(shape.typeName)
                if (name == target.sourceName) throw GradleException("[ARCVALIDATION_GRAPH] Recursive shared edge '$name'.")
                append("        if (${edge.access} != null) qualified.push(...new ${sharedAlias(name)}().validate(${edge.access}, ${edge.path}, ${edge.seen}));\n")
            }
            TypeShapeKind.SEQUENCE -> {
                val leaf = requireNotNull(shape.elementShape)
                val name = requireNotNull(leaf.typeName)
                append("        ${edge.access}?.forEach((entry, index) => {\n")
                append("            qualified.push(...new ${sharedAlias(name)}().validate(entry, ${edge.path} + '[' + index + ']', ${edge.seen}));\n        });\n")
            }
            TypeShapeKind.MAP -> throw GradleException("[ARCVALIDATION_GRAPH] Shared model maps are unsupported.")
        }
    }

    fun appendQueryValidator(out: StringBuilder, query: QueryDescriptor, target: ArtifactTarget, targets: List<ValidationTarget>) {
        val className = "${target.typeScriptName}Validator"
        val targetType = "${target.typeScriptName}Parameters"
        val parameters = query.parameters.filter { it.source == QueryParameterSource.CLIENT }
        if (parameters.any { shared.reaches(it.shape) }) {
            appendSharedValidator(
                out,
                SharedValidatorSpec(
                    className, "QueryValidator", targetType, null,
                    parameters.map { PropertyDescriptor(it.name, it.shape) }, targets, target, query = true
                )
            )
        } else {
            appendValidator(out, className, "QueryValidator", targetType, targets)
        }
    }

    fun appendValidator(out: StringBuilder, className: String, validatorType: String, targetType: String, targets: List<ValidationTarget>) {
        if (targets.isEmpty()) return
        out.append("export class $className extends $validatorType<$targetType> {\n")
        out.append("    constructor() {\n")
        out.append("        super();\n")
        targets.forEach { target ->
            target.rules.forEach { rule ->
                out.append("        this.ruleFor(c => c.${target.propertyName}).${rule.ruleName}(")
                out.append(renderValidationArguments(rule))
                out.append(")")
                rule.message?.let { message -> out.append(".withMessage(${typescriptString(message)})") }
                out.append(";\n")
            }
        }
        out.append("    }\n")
        out.appendOptionalQueryValidate(targetType, targets.filter(ValidationTarget::skipWhenUndefined))
        out.append("}\n\n")
    }

    private fun StringBuilder.appendOptionalQueryValidate(targetType: String, optionalTargets: List<ValidationTarget>) {
        if (optionalTargets.isEmpty()) return
        append("\n    validate(query: $targetType) {\n")
        append("        return super.validate(query).filter(result => !(\n")
        optionalTargets.forEachIndexed { index, target ->
            val prefix = if (index == 0) "            " else "            || "
            append("$prefix(query.${target.propertyName} === undefined && result.members.some(member => ")
            append("member === '${target.propertyName}' || member.startsWith('${target.propertyName}.')))\n")
        }
        append("        ));\n")
        append("    }\n")
    }

    private fun renderValidationArguments(rule: ValidationRuleDescriptor): String =
        rule.arguments.mapIndexed { index, argument ->
            if (rule.ruleName == "matches" && index == 0) {
                regularExpression(argument as? String ?: throw GradleException("Regular expression argument must be a string."))
            } else {
                renderArgument(argument, rule)
            }
        }.joinToString(", ")

    private fun renderArgument(argument: Any?, rule: ValidationRuleDescriptor): String = when (argument) {
        is String -> typescriptString(argument)
        is Number, is Boolean -> argument.toString()
        else -> throw GradleException("Unsupported argument '$argument' for Arc validation rule '${rule.ruleName}'.")
    }
}
