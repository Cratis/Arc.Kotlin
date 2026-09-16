// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSDeclarationContainer
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import io.cratis.arc.metadata.ValidationRuleDescriptor
import java.io.File

/** Replacement-only round snapshot. Retained discovery state consists solely of qualified names. */
internal class FluentValidationDiscovery(private val logger: ArcDiagnosticReporter, private val trace: ((String) -> Unit)? = null) {
    private val recordTypes = mutableMapOf<String, Map<String, String>?>()
    data class Snapshot(val local: List<FluentDeclaration>, val all: List<FluentDeclaration>, val deferred: List<KSAnnotated>)

    fun discover(resolver: Resolver, names: MutableSet<String>, imports: List<FluentDeclaration>, indexed: Boolean): Snapshot {
        fun classes(container: KSDeclarationContainer): Sequence<KSClassDeclaration> = container.declarations.flatMap {
            if (it is KSClassDeclaration) sequenceOf(it) + classes(it) else emptySequence()
        }
        resolver.getAllFiles().flatMap(::classes).forEach { declaration ->
            if (declaration.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == FluentValidationMetadata.BASE } ||
                declaration.superTypes.any { reference ->
                    val alias = reference.resolve().declaration as? com.google.devtools.ksp.symbol.KSTypeAlias
                    alias?.type?.resolve()?.declaration?.qualifiedName?.asString() == FluentValidationMetadata.BASE
                }) {
                declaration.qualifiedName?.asString()?.let(names::add)
            }
        }
        val deferred = mutableListOf<KSAnnotated>()
        val local = mutableListOf<FluentDeclaration>()
        val all = mutableListOf<FluentDeclaration>()
        for (name in (names + imports.map { it.validator }).sorted()) {
            val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
            if (declaration == null) {
                logger.error(ArcDiagnostic.FLUENT_METADATA, "Fluent validator '$name' from the dependency index cannot be resolved; supply matching compile dependencies.")
                continue
            }
            if (!declaration.validate({ _, _ -> true }, enableNewFeatures = false)) { deferred += declaration; continue }
            try {
                require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "use a canonical ASCII validator type name" }
                require(declaration.parentDeclaration == null && declaration.isPublic() && declaration.classKind == ClassKind.CLASS &&
                    declaration.typeParameters.isEmpty() && listOf(Modifier.OPEN, Modifier.ABSTRACT, Modifier.SEALED).none { it in declaration.modifiers }) {
                    "use a public final top-level directly typed validator"
                }
                val bases = declaration.superTypes.map { it.resolve() }.filter { it.declaration.qualifiedName?.asString() !in setOf("kotlin.Any", "java.lang.Object") }.toList()
                require(bases.size == 1 && bases.single().declaration.qualifiedName?.asString() == FluentValidationMetadata.BASE) {
                    "extend FluentModelValidator<Model> directly without additional interfaces"
                }
                val modelType = bases.single().arguments.singleOrNull()?.type?.resolve()
                if (modelType?.isError == true) { deferred += declaration; continue }
                val model = modelType?.declaration as? KSClassDeclaration
                require(model != null && !modelType.isMarkedNullable && modelType.arguments.isEmpty() && model.isPublic() &&
                    model.classKind == ClassKind.CLASS && Modifier.ABSTRACT !in model.modifiers && Modifier.VALUE !in model.modifiers && model.typeParameters.isEmpty()) {
                    "select one public concrete non-generic non-value model"
                }
                if (!model.validate({ _, _ -> true }, enableNewFeatures = false)) { deferred += declaration; continue }
                val modelName = requireNotNull(model.qualifiedName).asString()
                require(modelName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) { "use a canonical ASCII model type name" }
                val imported = imports.singleOrNull { it.validator == name }
                if (name !in names) {
                    require(imported != null && imported.model == modelName) { "indexed model disagrees with the declaration; rebuild the producer" }
                    require(declaration.getConstructors().any { it.isPublic() && it.parameters.isEmpty() }) { "indexed validator must have a public no-argument constructor" }
                    val members = imported.members.map { member ->
                        // The indexed producer proved its selected source members before export.
                        val type = memberType(resolver, model, member.name, sourceProofRequired = false)
                        require(type == member.runtimeType) { "indexed member '${member.name}' changed type; rebuild the producer" }
                        member.copy(rules = FluentValidationMetadata.normalize(member.name, type, member.rules))
                    }
                    all += imported.copy(members = members)
                    continue
                }
                require(indexed) { "supply '${FluentValidationMetadata.OPTION}' through the Arc Gradle plugin; shared validation requires a verified dependency index" }
                val path = declaration.containingFile?.filePath ?: throw FluentSourceRejected("source is unavailable")
                val file = File(path)
                require(file.isFile) { "source '$path' is unavailable" }
                val toolchain = try { FluentSourceParser.toolchain() } catch (exception: Exception) {
                    logger.error(ArcDiagnostic.FLUENT_METADATA, "Fluent validator '$name': parser dependency unavailable or unsupported (${exception.message}); use arc-ksp with kotlin-compiler-embeddable 2.4.20 on JDK17.", declaration)
                    continue
                }
                trace?.invoke("[ARC-FLUENT-PARSER] $toolchain")
                val chains = when (file.extension) {
                    "kt" -> FluentSourceParser.kotlin(file.readText(), declaration.packageName.asString(), declaration.simpleName.asString(), modelName) { packageName ->
                        @OptIn(KspExperimental::class)
                        resolver.getDeclarationsFromPackage(packageName).any { it.simpleName.asString() == "java" }
                    }
                    "java" -> FluentSourceParser.java(file.readText(), declaration.packageName.asString(), declaration.simpleName.asString(), modelName)
                    else -> throw FluentSourceRejected("unsupported source language")
                }
                val members = try { extractRules(resolver, model, chains) } catch (exception: IllegalArgumentException) {
                    logger.error(ArcDiagnostic.FLUENT_RULE, "Fluent validator '$name': ${exception.message}; use representable literal member rules.", declaration)
                    continue
                }
                val extracted = FluentDeclaration(name, modelName, members)
                require(imported == null || imported == extracted) { "local declaration conflicts with dependency metadata; remove the duplicate producer" }
                local += extracted
                all += extracted
            } catch (exception: IllegalArgumentException) {
                val code = if (name !in names || !indexed) ArcDiagnostic.FLUENT_METADATA else ArcDiagnostic.FLUENT_DECLARATION
                logger.error(code, "Fluent validator '$name': ${exception.message}; use the restricted literal constructor grammar.", declaration)
            } catch (exception: LinkageError) {
                logger.error(ArcDiagnostic.FLUENT_METADATA, "Fluent validator '$name': compiler parser unavailable (${exception.javaClass.simpleName}); use arc-ksp with its exact kotlin-compiler-embeddable 2.4.20 dependency on JDK17.", declaration)
            }
        }
        return Snapshot(local, all, deferred)
    }

    private fun extractRules(resolver: Resolver, model: KSClassDeclaration, chains: List<List<FluentCall>>): List<FluentMember> {
        val grouped = sortedMapOf<String, MutableList<ValidationRuleDescriptor>>()
        for (chain in chains) {
            val selector = chain.firstOrNull()
            require(selector?.name == "ruleFor" && selector.arguments.size == 1 && selector.arguments.single() is String) { "start each chain with ruleFor(\"member\")" }
            val member = selector.arguments.single() as String
            val type = memberType(resolver, model, member)
            val rules = mutableListOf<ValidationRuleDescriptor>()
            for (call in chain.drop(1)) {
                if (call.name == "withMessage") {
                    require(rules.isNotEmpty() && call.arguments.size == 1 && call.arguments.single() is String) { "member '$member': withMessage requires a preceding rule and one literal string" }
                    val previous = rules.last()
                    rules[rules.lastIndex] = ValidationRuleDescriptor(previous.ruleName, previous.arguments, call.arguments.single() as String)
                } else {
                    try {
                        rules += FluentValidationMetadata.normalize(member, type, listOf(ValidationRuleDescriptor(call.name, call.arguments)))
                    } catch (exception: IllegalArgumentException) {
                        throw FluentSourceRejected("member '$member', call '${call.name}': ${exception.message}")
                    }
                }
            }
            require(rules.isNotEmpty()) { "member '$member': declare at least one rule" }
            grouped.getOrPut(member) { mutableListOf() }.addAll(rules)
        }
        require(grouped.isNotEmpty()) { "declare at least one fluent rule" }
        return grouped.map { (member, rules) ->
            val type = memberType(resolver, model, member)
            FluentMember(member, type, FluentValidationMetadata.normalize(member, type, rules))
        }
    }

    companion object {
        fun requireWireMember(model: KSClassDeclaration, name: String) {
            val source = model.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)
                ?: throw FluentSourceRejected("member '$name' has no source accessor proof; declare rules with the model in its producer or use a server-only ModelValidator")
            val members = when (source.extension) {
                "kt" -> FluentSourceParser.kotlinWireMembers(source.readText(), model.packageName.asString(), model.simpleName.asString())
                "java" -> FluentSourceParser.javaWireMembers(source.readText(), model.packageName.asString(), model.simpleName.asString())
                else -> emptySet()
            }
            require(name in members) { "member '$name' is computed; use backed wire state with a default or identity accessor, not an inherited or unproved member, or use a server-only ModelValidator" }
        }
    }

    @OptIn(KspExperimental::class)
    private fun memberType(resolver: Resolver, model: KSClassDeclaration, name: String, sourceProofRequired: Boolean = true): String {
        if (sourceProofRequired) requireWireMember(model, name)
        require(io.cratis.arc.json.ArcCamelCase.convert(name) == name) { "member '$name' changes its name on the wire; use an unchanged camel-case member" }
        val modelName = requireNotNull(model.qualifiedName).asString()
        if (!recordTypes.containsKey(modelName)) {
            val source = model.containingFile?.filePath?.let(::File)?.takeIf { it.extension == "java" && it.isFile }
            recordTypes[modelName] = source?.let {
                FluentSourceParser.javaRecordTypes(it.readText(), model.packageName.asString(), model.simpleName.asString()) { text, imports ->
                    val candidates = if ('.' in text) listOf(text) else imports.filter { imported -> imported.substringAfterLast('.') == text } +
                        listOf("${model.packageName.asString()}.$text", "java.lang.$text") +
                        imports.filter { it.endsWith(".*") }.map { it.removeSuffix("*") + text }
                    candidates.firstOrNull { candidate -> resolver.getClassDeclarationByName(resolver.getKSNameFromString(candidate)) != null }
                        ?: throw FluentSourceRejected("record member type '$text' cannot be resolved")
                }
            }
        }
        recordTypes[modelName]?.let { components ->
            return components[name] ?: throw FluentSourceRejected("member '$name' must name a record component")
        }
        val isRecord = model.superTypes.any { it.resolve().declaration.qualifiedName?.asString() == "java.lang.Record" }
        val property = model.getAllProperties().firstOrNull { it.simpleName.asString() == name &&
            (it.isPublic() || isRecord) && Modifier.JAVA_STATIC !in it.modifiers }
        val accessor = if (isRecord) model.getAllFunctions().firstOrNull { it.simpleName.asString() == name && it.parameters.isEmpty() && it.isPublic() } else null
        val member: KSDeclaration = accessor ?: property ?: throw FluentSourceRejected("member '$name' must be a public readable model property, record component or field")
        val ignored = member.annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == "com.fasterxml.jackson.annotation.JsonIgnore" }
        require(!ignored) { "member '$name' is ignored on the wire; select a readable wire member" }
        val signature = resolver.mapToJvmSignature(member) ?: throw FluentSourceRejected("member '$name' has no provable JVM type")
        val field = signature.substringAfterLast(')')
        val semantic = property?.type?.resolve()?.declaration as? KSClassDeclaration
        require(semantic == null || Modifier.VALUE !in semantic.modifiers) {
            "member '$name' has an erased inline-value type; use an ordinary member or a server-only ModelValidator"
        }
        return when {
            field.startsWith('[') -> field.replace('/', '.')
            field.startsWith('L') && field.endsWith(';') -> field.substring(1, field.length - 1).replace('/', '.')
            else -> mapOf("Z" to "boolean", "B" to "byte", "C" to "char", "S" to "short", "I" to "int", "J" to "long", "F" to "float", "D" to "double")[field]
                ?: throw FluentSourceRejected("member '$name' has unsupported JVM signature '$signature'")
        }
    }
}
