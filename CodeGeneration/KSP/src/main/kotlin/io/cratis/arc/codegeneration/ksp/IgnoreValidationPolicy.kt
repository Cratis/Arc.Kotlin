// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSPropertyGetter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.ClassKind
import java.io.File

/** Compiler counterpart of ValidationMemberPolicy.isIgnored: symbol metadata only, never reflection. */
internal object IgnoreValidationPolicy {
    const val ANNOTATION = "io.cratis.arc.validation.IgnoreValidation"

    fun validateTargets(resolver: Resolver, logger: ArcDiagnosticReporter, owners: MutableSet<String>, topLevelProperties: MutableSet<String>) {
        val discovered = resolver.getSymbolsWithAnnotation(ANNOTATION).toList()
        discovered.forEach { symbol ->
            val declaration = when (symbol) {
                is KSPropertyGetter -> symbol.receiver
                is KSDeclaration -> symbol
                else -> null
            }
            var owner = declaration?.parentDeclaration
            while (owner != null && owner !is KSClassDeclaration) owner = owner.parentDeclaration
            owner?.qualifiedName?.asString()?.let(owners::add)
            if (declaration is KSPropertyDeclaration && declaration.parentDeclaration == null)
                declaration.qualifiedName?.asString()?.let(topLevelProperties::add)
        }
        // Re-resolve stable identities each round: annotation queries only return the round's symbols.
        // A terminal metadata round must not erase an invalid annotation found before generated invokers.
        val retained = owners.flatMap { name ->
            val owner = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
            owner?.declarations?.flatMap { declaration ->
                if (declaration is KSPropertyDeclaration) sequenceOf(declaration) + listOfNotNull(declaration.getter, declaration.setter).asSequence()
                else sequenceOf(declaration)
            }?.filter(::hasAnnotation)?.toList().orEmpty()
        } + topLevelProperties.mapNotNull { resolver.getPropertyDeclarationByName(resolver.getKSNameFromString(it)) }
            .flatMap { listOfNotNull(it, it.getter) }.filter(::hasAnnotation)
        (discovered + retained).distinct().forEach { symbol ->
            val valid = when (symbol) {
                is KSPropertyDeclaration -> symbol.parentDeclaration is KSClassDeclaration && Modifier.JAVA_STATIC !in symbol.modifiers
                is KSPropertyGetter -> symbol.receiver.parentDeclaration is KSClassDeclaration
                is KSFunctionDeclaration -> memberName(symbol) != null
                is KSValueParameter -> {
                    val constructor = symbol.parent as? KSFunctionDeclaration
                    val owner = constructor?.parentDeclaration as? KSClassDeclaration
                    owner != null && recordMembers(owner).contains(symbol.name?.asString())
                }
                else -> false
            }
            if (!valid) logger.error(ArcDiagnostic.IGNORE_VALIDATION,
                "@IgnoreValidation requires an instance property, field, record accessor or bean getter; move the annotation to a supported member edge.", symbol)
        }
    }

    fun validateWireOwner(owner: KSClassDeclaration, logger: ArcDiagnosticReporter) {
        if (owner.classKind == ClassKind.INTERFACE) return
        val fields = owner.getDeclaredProperties().map { it.simpleName.asString() }.toSet()
        val records = recordMembers(owner)
        owner.getDeclaredFunctions().filter(::hasAnnotation).forEach { function ->
            val name = memberName(function)
            if (name == null || name !in fields && name !in records) logger.error(ArcDiagnostic.IGNORE_VALIDATION,
                "@IgnoreValidation accessor '${function.qualifiedName?.asString()}' has no supported declared wire member; use a backed property or record component, or keep this bean outside generated artifacts.", function)
        }
    }

    @OptIn(com.google.devtools.ksp.KspExperimental::class)
    fun isIgnored(annotated: Iterable<KSAnnotated>, node: KSNode, resolver: Resolver?, logger: ArcDiagnosticReporter): Boolean {
        val declaration = node as? KSDeclaration
        val owner = declaration?.parentDeclaration as? KSClassDeclaration
        val property = declaration as? KSPropertyDeclaration
        val function = declaration as? KSFunctionDeclaration
        val name = property?.simpleName?.asString() ?: function?.let(::memberName)
        if (owner == null || name == null) return annotated.any(::hasAnnotation)
        val parents = mutableListOf<KSClassDeclaration>()
        val visited = mutableSetOf<String>()
        fun collect(type: KSClassDeclaration) {
            type.superTypes.mapNotNull { it.resolve().declaration as? KSClassDeclaration }.forEach { parent ->
                if (visited.add(parent.qualifiedName?.asString().orEmpty())) { parents += parent; collect(parent) }
            }
        }
        collect(owner)
        val ownGetters = owner.getDeclaredFunctions().filter { memberName(it) == name }.toList()
        var ignored = annotated.any(::hasAnnotation) || ownGetters.any(::hasAnnotation)
        parents.forEach { parent ->
            parent.getDeclaredProperties().forEach propertyLoop@ { inherited ->
                val genuine = property != null && resolver?.overrides(property, inherited, owner) == true ||
                    (ownGetters + listOfNotNull(function)).any { method ->
                        resolver?.overrides(method, inherited, owner) == true ||
                            // KSP does not report Java-function/Kotlin-property overrides. Match the
                            // inherited JVM accessor signature, not a guessed bean alias.
                            (inherited.getter != null && Modifier.PRIVATE !in inherited.modifiers &&
                                (parent.classKind == ClassKind.INTERFACE || Modifier.OPEN in inherited.modifiers || Modifier.ABSTRACT in inherited.modifiers) &&
                                resolver?.getJvmName(requireNotNull(inherited.getter)) == method.simpleName.asString() &&
                                method.returnType?.resolve()?.let { inherited.type.resolve().isAssignableFrom(it) } == true)
                    }
                if (inherited.simpleName.asString() != name && !genuine) return@propertyLoop
                if (genuine) ignored = ignored || hasAnnotation(inherited) || inherited.getter?.let(::hasAnnotation) == true
                else if (property != null && (ignored || hasAnnotation(inherited) || inherited.getter?.let(::hasAnnotation) == true)) {
                    logger.error(ArcDiagnostic.IGNORE_VALIDATION,
                        "Ambiguous @IgnoreValidation member '${owner.qualifiedName?.asString()}.$name' hides inherited state; rename hidden members or use a genuine property override.", node)
                }
            }
            parent.getDeclaredFunctions().filter { hasAnnotation(it) }.forEach { inherited ->
                if ((ownGetters + listOfNotNull(function)).any { resolver?.overrides(it, inherited, owner) == true }) ignored = true
            }
        }
        if (ignored && ownGetters.map { it.simpleName.asString() }.distinct().size > 1) logger.error(ArcDiagnostic.IGNORE_VALIDATION,
            "Ambiguous @IgnoreValidation member '${owner.qualifiedName?.asString()}.$name' has multiple getter names; use one logical accessor.", node)
        return ignored
    }

    private fun hasAnnotation(symbol: KSAnnotated): Boolean = symbol.annotations.any {
        it.annotationType.resolve().declaration.qualifiedName?.asString() == ANNOTATION
    }

    private fun recordMembers(owner: KSClassDeclaration): Set<String> {
        if (owner.origin == Origin.JAVA) {
            val source = owner.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
            if (source != null) return parseJavaRecordProperties(source, owner.simpleName.asString())?.map { it.name }?.toSet().orEmpty()
        }
        if (owner.superTypes.none { it.resolve().declaration.qualifiedName?.asString() == "java.lang.Record" }) return emptySet()
        return owner.getConstructors()
            .flatMap { it.parameters.asSequence() }.mapNotNull { it.name?.asString() }.toSet() +
            owner.getDeclaredProperties().map { it.simpleName.asString() }.toSet()
    }

    private fun memberName(function: KSFunctionDeclaration): String? {
        if (function.parameters.isNotEmpty() || Modifier.JAVA_STATIC in function.modifiers || Modifier.PRIVATE in function.modifiers) return null
        val type = function.returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: return null
        if (type in setOf("kotlin.Unit", "java.lang.Void", "void")) return null
        val name = function.simpleName.asString()
        val suffix = when {
            name.startsWith("get") && name.length > 3 -> name.substring(3)
            name.startsWith("is") && name.length > 2 && type in setOf("kotlin.Boolean", "boolean") -> name.substring(2)
            else -> {
                val owner = function.parentDeclaration as? KSClassDeclaration ?: return null
                return name.takeIf { it in recordMembers(owner) }
            }
        }
        return if (suffix.length > 1 && suffix[0].isUpperCase() && suffix[1].isUpperCase()) suffix
        else suffix.replaceFirstChar(Char::lowercase)
    }
}
