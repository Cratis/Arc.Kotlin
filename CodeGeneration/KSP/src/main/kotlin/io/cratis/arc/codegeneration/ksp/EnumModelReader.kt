// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.File

internal fun KSAnnotated.intAnnotationArgument(qualifiedName: String): Int? = annotations
    .firstOrNull { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }
    ?.arguments?.firstOrNull { argument -> argument.name?.asString() == "value" || argument.name == null }
    ?.value
    ?.let { value -> (value as? Number)?.toInt() }

/** Reads an enum declaration into its model, proving the wire value of every member of an `ArcEnum`. */
internal class EnumModelReader(private val logger: ArcDiagnosticReporter) {
    /** Returns the enum model, or null after reporting every member whose value cannot be determined. */
    fun read(declaration: KSClassDeclaration): EnumModel? {
        val qualifiedName = requireNotNull(declaration.qualifiedName).asString()
        val entries = declaration.declarations.filterIsInstance<KSClassDeclaration>()
            .filter { candidate -> candidate.classKind == ClassKind.ENUM_ENTRY }
            .toList()
        val implementsArcEnum = declaration.superTypes.any { reference ->
            reference.resolve().declaration.qualifiedName?.asString() == ARC_ENUM_TYPE
        }
        val constructorValues = constructorValues(declaration, implementsArcEnum)
        val members = entries.mapIndexed { ordinal, entry ->
            memberOf(qualifiedName, entry, if (implementsArcEnum) null else ordinal, constructorValues[entry.simpleName.asString()])
        }
        if (members.any { member -> member == null }) return null
        return EnumModel(
            name = declaration.simpleName.asString(),
            fullyQualifiedName = qualifiedName,
            location = qualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
            members = members.filterNotNull(),
            isFlags = declaration.hasAnnotation(FLAGS_ANNOTATION),
            summary = DocumentationSummaryParser.parse(declaration.docString).summary
        )
    }

    private fun constructorValues(declaration: KSClassDeclaration, implementsArcEnum: Boolean): Map<String, Int> {
        val constructorCanSupplyValue = declaration.primaryConstructor?.parameters?.singleOrNull()
            ?.type?.resolve()?.declaration?.qualifiedName?.asString() in INTEGER_TYPE_NAMES
        if (!implementsArcEnum || !constructorCanSupplyValue) return emptyMap()
        return declaration.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
            ?.let { source -> parseEnumConstructorValues(source, declaration.simpleName.asString()) }
            .orEmpty()
    }

    /** [ordinalValue] is the implicit value of a plain enum, and null for an `ArcEnum` whose values must be proven. */
    private fun memberOf(
        qualifiedName: String,
        entry: KSClassDeclaration,
        ordinalValue: Int?,
        constructorValue: Int?
    ): EnumMemberModel? {
        val name = entry.simpleName.asString()
        if (ordinalValue != null) return EnumMemberModel(name, ordinalValue)
        val annotatedValue = entry.intAnnotationArgument(ARC_ENUM_VALUE_ANNOTATION)
        if (annotatedValue != null && constructorValue != null && annotatedValue != constructorValue) {
            logger.error(
                ArcDiagnostic.ENUM_VALUE,
                "Enum '$qualifiedName.$name' has ambiguous Arc values: constructor $constructorValue and " +
                    "@ArcEnumValue $annotatedValue.",
                entry
            )
            return null
        }
        val value = annotatedValue ?: constructorValue
        if (value == null) {
            logger.error(
                ArcDiagnostic.ENUM_VALUE,
                "Enum '$qualifiedName.$name' implements ArcEnum, but its value cannot be proven from a " +
                    "single integer-literal constructor argument; add @ArcEnumValue with the wire value.",
                entry
            )
            return null
        }
        return EnumMemberModel(name, value)
    }
}
