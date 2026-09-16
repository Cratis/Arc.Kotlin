// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.validation.FluentValidationMember
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

internal data class FluentMember(val name: String, val runtimeType: String, val rules: List<ValidationRuleDescriptor>)
internal data class FluentDeclaration(val validator: String, val model: String, val members: List<FluentMember>)

/** Format-1 compiler declarations, separate from the unchanged artifact manifest schema. */
internal object FluentValidationMetadata {
    const val OPTION = "arc.fluentValidationMetadata"
    const val PREFIX = "META-INF/cratis/arc-fluent-validation/"
    const val BASE = "io.cratis.arc.validation.FluentModelValidator"
    private val namePattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    fun readIndex(option: String): List<FluentDeclaration> {
        val uri = URI(option)
        require(uri.isAbsolute && uri.scheme == "file" && uri.fragment == null && uri.query == null) { "supply an absolute file URI" }
        val root = mapper.readTree(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(File(uri).readBytes())).toString())
        fields(root, setOf("formatVersion", "modules")); version(root)
        require(root.path("modules").isArray) { "modules must be an array" }
        val modules = sortedMapOf<String, List<FluentDeclaration>>()
        val declarations = sortedMapOf<String, FluentDeclaration>()
        root.path("modules").forEach { module ->
            fields(module, setOf("formatVersion", "moduleName", "validators")); version(module)
            val name = string(module.path("moduleName"))
            require(validateModuleName(name) != null) { "invalid moduleName '$name'" }
            require(module.path("validators").isArray && !module.path("validators").isEmpty) { "validators must be a nonempty array" }
            val parsed = module.path("validators").toList().map(::declaration).sortedBy { it.validator }
            require(parsed.map { it.validator }.distinct().size == parsed.size) { "duplicate validator declarations" }
            val previous = modules.putIfAbsent(name, parsed)
            require(previous == null || previous == parsed) { "conflicting fluent module '$name'" }
            parsed.forEach { entry ->
                val prior = declarations.putIfAbsent(entry.validator, entry)
                require(prior == null || prior == entry) { "conflicting fluent declaration '${entry.validator}'" }
            }
        }
        return declarations.values.toList()
    }

    private fun declaration(node: JsonNode): FluentDeclaration {
        fields(node, setOf("validatorTypeName", "modelTypeName", "members"))
        val validator = type(node.path("validatorTypeName"))
        val model = type(node.path("modelTypeName"))
        require(node.path("members").isArray && !node.path("members").isEmpty) { "members must be a nonempty array" }
        val members = node.path("members").toList().map { member ->
            fields(member, setOf("member", "memberTypeName", "rules"))
            val name = string(member.path("member"))
            val runtimeType = string(member.path("memberTypeName"))
            require(runtimeType.matches(Regex("(?:\\[*(?:[ZBCSIJFD]|L[A-Za-z_$][A-Za-z0-9_.$]*;)|[A-Za-z_$][A-Za-z0-9_.$]*)"))) { "invalid member JVM type" }
            require(member.path("rules").isArray) { "rules must be an array" }
            val rules = member.path("rules").toList().map { rule ->
                fields(rule, setOf("ruleName", "arguments", "message"))
                val ruleName = string(rule.path("ruleName"))
                require(rule.path("arguments").isArray) { "arguments must be an array" }
                val args = rule.path("arguments").toList().map { argument ->
                    when {
                        argument.isString -> argument.asString()
                        argument.isIntegralNumber && ruleName in setOf("minLength", "maxLength", "length") -> {
                            require(argument.canConvertToInt()) { "length must fit Int" }; argument.intValue()
                        }
                        argument.isNumber -> argument.numberValue()
                        else -> error("arguments must be scalar strings or numbers")
                    }
                }
                val message = rule.path("message")
                require(message.isNull || message.isString) { "message must be null or string" }
                ValidationRuleDescriptor(ruleName, args, if (message.isNull) null else message.asString())
            }
            FluentMember(name, runtimeType, normalize(name, runtimeType, rules))
        }.sortedBy { it.name }
        require(members.map { it.name }.distinct().size == members.size) { "duplicate fluent member" }
        return FluentDeclaration(validator, model, members)
    }

    fun normalize(member: String, runtimeType: String, rules: List<ValidationRuleDescriptor>): List<ValidationRuleDescriptor> =
        FluentValidationMember(member, validationType(runtimeType), rules).rules

    /** Only framework/JDK types are touched. Unknown application types accept notNull only; never load them. */
    private fun validationType(name: String): Class<*> = when (name) {
        "java.lang.String" -> String::class.java
        "byte", "java.lang.Byte" -> Byte::class.javaObjectType
        "short", "java.lang.Short" -> Short::class.javaObjectType
        "int", "java.lang.Integer" -> Int::class.javaObjectType
        "long", "java.lang.Long" -> Long::class.javaObjectType
        "float", "java.lang.Float" -> Float::class.javaObjectType
        "double", "java.lang.Double" -> Double::class.javaObjectType
        "java.math.BigDecimal" -> java.math.BigDecimal::class.java
        "java.math.BigInteger" -> java.math.BigInteger::class.java
        "java.util.List", "java.util.Set", "java.util.Collection", "java.util.ArrayList", "java.util.HashSet" -> Collection::class.java
        else -> if (name.startsWith('[')) Array<Any>::class.java else Any::class.java
    }

    fun scopeDocument(module: String, indexed: Boolean, validators: List<String>): String = mapper.writeValueAsString(linkedMapOf(
        "formatVersion" to 1, "moduleName" to module, "indexed" to indexed, "validators" to validators.distinct().sorted())) + "\n"

    fun document(module: String, declarations: List<FluentDeclaration>): String = mapper.writeValueAsString(linkedMapOf(
        "formatVersion" to 1, "moduleName" to module, "validators" to declarations.sortedBy { it.validator }.map { declaration ->
            linkedMapOf("validatorTypeName" to declaration.validator, "modelTypeName" to declaration.model,
                "members" to declaration.members.map { member -> linkedMapOf("member" to member.name, "memberTypeName" to member.runtimeType,
                    "rules" to member.rules.map { rule -> linkedMapOf("ruleName" to rule.ruleName, "arguments" to rule.arguments, "message" to rule.message) }) })
        })) + "\n"

    private fun string(node: JsonNode): String {
        require(node.isString) { "expected a string" }
        return node.asString()
    }
    private fun type(node: JsonNode): String {
        require(node.isString && namePattern.matches(node.asString())) { "use canonical public type names" }
        return node.asString()
    }
    private fun fields(node: JsonNode, expected: Set<String>) {
        require(node.isObject && node.properties().map { it.key }.toSet() == expected) { "expected exactly $expected" }
    }
    private fun version(node: JsonNode) {
        val value = node.path("formatVersion")
        require(value.isIntegralNumber && value.canConvertToInt() && value.intValue() == 1) { "expected formatVersion=1" }
    }
}
