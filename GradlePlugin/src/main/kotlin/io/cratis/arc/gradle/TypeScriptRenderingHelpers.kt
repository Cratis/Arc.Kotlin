// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.metadata.ValidationRuleDescriptor

// Rendering helpers that do not depend on generation state.

internal fun StringBuilder.appendStringMapGuard() {
    append("const reservedArcStringMapKeys = new Set(['__proto__', 'prototype', 'constructor']);\n\n")
    append("function sanitizeArcStringMap<T>(value: T, path: string, seen = new WeakMap<object, unknown>()): T {\n")
    append("    if (value === null || typeof value !== 'object') return value;\n")
    append("    const previous = seen.get(value);\n")
    append("    if (previous !== undefined) return previous as T;\n")
    append("    if (Array.isArray(value)) {\n")
    append("        if (Object.getPrototypeOf(value) !== Array.prototype) {\n")
    append("            throw new TypeError(`String map sequence at '\${path}' must use Array.prototype.`);\n")
    append("        }\n")
    append("        for (const key of Object.getOwnPropertyNames(value)) {\n")
    append("            if (reservedArcStringMapKeys.has(key)) {\n")
    append("                throw new TypeError(`String map sequence at '\${path}' contains reserved key '\${key}'.`);\n")
    append("            }\n")
    append("        }\n")
    append("        const normalized: unknown[] = [];\n")
    append("        seen.set(value, normalized);\n")
    append("        value.forEach((entry, index) => normalized.push(sanitizeArcStringMap(entry, `\${path}[\${index}]`, seen)));\n")
    append("        return normalized as T;\n")
    append("    }\n")
    append("    const prototype = Object.getPrototypeOf(value);\n")
    append("    if (prototype !== Object.prototype && prototype !== null) {\n")
    append("        throw new TypeError(`String map at '\${path}' must use Object.prototype or a null prototype.`);\n")
    append("    }\n")
    append("    for (const key of Object.getOwnPropertyNames(value)) {\n")
    append("        if (reservedArcStringMapKeys.has(key)) {\n")
    append("            throw new TypeError(`String map at '\${path}' contains reserved key '\${key}'.`);\n")
    append("        }\n")
    append("    }\n")
    append("    const normalized: Record<string, unknown> = {};\n")
    append("    seen.set(value, normalized);\n")
    append("    for (const key of Object.keys(value)) {\n")
    append("        normalized[key] = sanitizeArcStringMap((value as Record<string, unknown>)[key], `\${path}.\${key}`, seen);\n")
    append("    }\n")
    append("    return normalized as T;\n")
    append("}\n\n")
}

internal fun TypeShapeDescriptor.containsMap(): Boolean = when (kind) {
    TypeShapeKind.VALUE -> false
    TypeShapeKind.SEQUENCE -> requireNotNull(elementShape).containsMap()
    TypeShapeKind.MAP -> true
}

/**
 * Emits one source documentation summary as a single-line JSDoc comment.
 *
 * The summary is guaranteed by [io.cratis.arc.metadata.DocumentationSummaries] to be one line, so the comment
 * cannot span lines; [escapeDocumentation] neutralizes the two sequences a JSDoc reader would otherwise act on.
 */
internal fun StringBuilder.appendDocumentation(summary: String?, indent: String = "") {
    if (summary == null) return
    append(indent)
    append("/** ")
    append(escapeDocumentation(summary))
    append(" */\n")
}

internal fun escapeDocumentation(summary: String): String = buildString(summary.length + 8) {
    summary.forEachIndexed { index, character ->
        when {
            // A comment terminator inside the text would end the comment and spill code into the file.
            character == '/' && index > 0 && summary[index - 1] == '*' -> append(" /")
            // An at sign in tag position would be read as a JSDoc block tag rather than as prose.
            character == '@' && (index == 0 || summary[index - 1].isWhitespace()) -> append("\\@")
            else -> append(character)
        }
    }
}

internal fun StringBuilder.appendGeneratedHeader() {
    append("/*---------------------------------------------------------------------------------------------\n")
    append(" *  **DO NOT EDIT** - This file is an automatically generated file.\n")
    append(" *--------------------------------------------------------------------------------------------*/\n\n")
}

internal fun routeParameters(route: String, properties: List<PropertyDescriptor>): List<String> {
    val propertyNames = properties.map { lowerCamel(it.name) }.toSet()
    return ROUTE_PARAMETER.findAll(route)
        .map { it.groupValues[1] }
        .filter { it in propertyNames }
        .distinct()
        .toList()
}

internal fun StringBuilder.appendPackageImports(
    types: Collection<TypeScriptType>,
    additionalImports: Collection<TypeScriptValueImport> = emptyList()
) {
    val imports = (types.flatMap { type -> type.recursiveTypes() }.mapNotNull(TypeScriptType::valueImport) +
        additionalImports).distinct()
    val importsByPackage = imports.groupBy(TypeScriptValueImport::packageName)
    importsByPackage.keys.sortedWith(
        compareBy<String, String>(String.CASE_INSENSITIVE_ORDER) { it }.thenBy { it }
    ).forEach { packageName ->
        val names = importsByPackage.getValue(packageName).sortedWith(
            compareBy<TypeScriptValueImport> { valueImport ->
                if (valueImport.packageName == FUNDAMENTALS_PACKAGE) {
                    FUNDAMENTALS_IMPORT_ORDER[valueImport.name] ?: FUNDAMENTALS_IMPORT_ORDER.size
                } else {
                    0
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER, TypeScriptValueImport::name)
                .thenBy(TypeScriptValueImport::name)
        ).groupBy(TypeScriptValueImport::name).map { (name, references) ->
            if (references.all(TypeScriptValueImport::typeOnly)) "type $name" else name
        }
        append("import { ${names.joinToString(", ")} } from '$packageName';\n")
    }
}

internal fun relativeImport(from: List<String>, target: ArtifactTarget): String {
    val common = from.zip(target.directory).takeWhile { (source, destination) -> source == destination }.size
    val upward = "../".repeat(from.size - common)
    val downward = (target.directory.drop(common) + target.fileStem).joinToString("/")
    return if (upward.isEmpty()) "./$downward" else "$upward$downward"
}

internal fun sameRule(left: ValidationRuleDescriptor, right: ValidationRuleDescriptor): Boolean =
    left.ruleName == right.ruleName && left.message == right.message && left.arguments.size == right.arguments.size &&
        left.arguments.zip(right.arguments).all { (a, b) ->
            if (a is Number && b is Number) a.toString().toBigDecimal().compareTo(b.toString().toBigDecimal()) == 0 else a == b
        }
