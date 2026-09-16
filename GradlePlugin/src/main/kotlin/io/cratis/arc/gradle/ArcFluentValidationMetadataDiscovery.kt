// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.validation.FluentValidationMember
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.jar.JarFile
import org.gradle.api.GradleException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Strict declaration inventory. Reads class-file headers, never loads or executes application classes. */
internal object ArcFluentValidationMetadataDiscovery {
    private const val PREFIX = "META-INF/cratis/arc-fluent-validation/"
    private const val SCOPE_PREFIX = "META-INF/cratis/arc-fluent-validation-scope/"
    private const val BASE = "io/cratis/arc/validation/FluentModelValidator"
    private val typeName = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    internal data class Scope(val indexed: Boolean, val validators: Set<String>)
    internal data class Inventory(val documents: Map<String, String>, val validators: Map<String, JsonNode>, val classes: Set<String>, val scopes: Map<String, Scope>)

    fun extract(compile: Iterable<File>, runtime: Iterable<File>): ByteArray {
        val compiled = inventory(compile)
        val running = inventory(runtime)
        requireMetadata(compiled.classes == running.classes,
            "Shared fluent declarations must be on both compile and runtime classpaths; runtime-only=${running.classes - compiled.classes}, compile-only=${compiled.classes - running.classes}")
        compiled.validators.forEach { (name, descriptor) -> requireMetadata(descriptor == running.validators[name], "Compile/runtime fluent metadata differs for '$name'") }
        return ("{\"formatVersion\":1,\"modules\":[${compiled.documents.values.joinToString(",") }]}\n").toByteArray(Charsets.UTF_8)
    }

    fun inventory(artifacts: Iterable<File>): Inventory {
        val resources = mutableListOf<Triple<String, String, ByteArray>>()
        val scopeResources = mutableListOf<Triple<String, String, ByteArray>>()
        val classes = sortedSetOf<String>()
        val moduleClasses = mutableMapOf<String, Set<String>>()
        val serviceModules = mutableSetOf<String>()
        val servicePath = "META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule"
        fun accept(path: String, origin: String, bytes: ByteArray) {
            if (path.startsWith(PREFIX) && path.endsWith(".json")) resources += Triple(path, origin, bytes)
            else if (path.startsWith(SCOPE_PREFIX) && path.endsWith(".json")) scopeResources += Triple(path, origin, bytes)
            else if (path == servicePath) {
                serviceModules += Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().lineSequence()
                    .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
            } else if (path.endsWith(".class")) {
                val header = try { classHeader(bytes) } catch (exception: Exception) {
                    throw GradleException("Unreadable class header '$origin'; shared validation inventory cannot prove completeness.", exception)
                }
                if (header.parent == "io/cratis/arc/artifacts/ArcArtifactModule") moduleClasses[header.name.replace('/', '.')] = header.references
                if (header.parent == BASE) {
                    requireMetadata(!path.startsWith("META-INF/versions/"), "Multi-release fluent validator '$origin' is unsupported")
                    requireMetadata(path == "${header.name}.class", "Fluent class path disagrees with its bytecode identity in '$origin'")
                    classes += header.name.replace('/', '.')
                }
            }
        }
        for (file in artifacts.distinctBy { it.absoluteFile.normalize() }) {
            when {
                file.isDirectory -> file.walkTopDown().filter { it.isFile && (it.extension == "class" || it.relativeTo(file).invariantSeparatorsPath == servicePath || it.extension == "json" && (it.relativeTo(file).invariantSeparatorsPath.startsWith(PREFIX) || it.relativeTo(file).invariantSeparatorsPath.startsWith(SCOPE_PREFIX))) }
                    .forEach { accept(it.relativeTo(file).invariantSeparatorsPath, it.path, it.readBytes()) }
                file.isFile && file.extension.equals("jar", true) -> JarFile(file).use { jar ->
                    jar.entries().asSequence().filter { !it.isDirectory && (it.name.endsWith(".class") || it.name == servicePath || (it.name.startsWith(PREFIX) || it.name.startsWith(SCOPE_PREFIX)) && it.name.endsWith(".json")) }
                        .forEach { entry -> accept(entry.name, "${file.path}!/${entry.name}", jar.getInputStream(entry).use { it.readBytes() }) }
                }
                !file.exists() -> throw GradleException("Missing shared validation dependency artifact '$file'.")
            }
        }
        val modules = sortedMapOf<String, String>()
        val validators = sortedMapOf<String, JsonNode>()
        for ((resource, origin, bytes) in resources.sortedBy { it.first }) {
            try {
                val root = mapper.readTree(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
                fields(root, setOf("formatVersion", "moduleName", "validators"))
                val version = root.path("formatVersion")
                require(version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1) { "expected formatVersion=1" }
                val module = root.path("moduleName")
                require(module.isString && safeModuleName(module.asString())) { "moduleName must be a safe identifier" }
                require(resource == "$PREFIX${module.asString()}.json") { "resource path disagrees with moduleName" }
                require(root.path("validators").isArray && !root.path("validators").isEmpty) { "validators must be a nonempty array" }
                val local = sortedMapOf<String, Any>()
                root.path("validators").forEach { declaration ->
                    fields(declaration, setOf("validatorTypeName", "modelTypeName", "members"))
                    val name = type(declaration.path("validatorTypeName"))
                    val model = type(declaration.path("modelTypeName"))
                    require(declaration.path("members").isArray && !declaration.path("members").isEmpty) { "members must be a nonempty array" }
                    val members = sortedMapOf<String, Any>()
                    declaration.path("members").forEach { member ->
                        fields(member, setOf("member", "memberTypeName", "rules"))
                        val memberName = string(member.path("member"))
                        val runtimeType = string(member.path("memberTypeName"))
                        require(runtimeType.matches(Regex("(?:\\[*(?:[ZBCSIJFD]|L[A-Za-z_$][A-Za-z0-9_.$]*;)|[A-Za-z_$][A-Za-z0-9_.$]*)"))) { "invalid JVM member type" }
                        require(member.path("rules").isArray) { "rules must be an array" }
                        val parsed = member.path("rules").toList().map { rule ->
                            fields(rule, setOf("ruleName", "arguments", "message"))
                            val ruleName = string(rule.path("ruleName"))
                            require(rule.path("arguments").isArray) { "arguments must be an array" }
                            val arguments = rule.path("arguments").toList().map { argument -> when {
                                argument.isString -> argument.asString()
                                argument.isIntegralNumber && ruleName in setOf("minLength", "maxLength", "length") -> {
                                    require(argument.canConvertToInt()) { "length must fit Int" }; argument.intValue()
                                }
                                argument.isNumber -> argument.numberValue()
                                else -> error("arguments must be scalar strings or numbers")
                            } }
                            val message = rule.path("message")
                            require(message.isString || message.isNull) { "message must be null or string" }
                            ValidationRuleDescriptor(ruleName, arguments, if (message.isNull) null else message.asString())
                        }
                        val normalized = FluentValidationMember(memberName, validationType(runtimeType), parsed).rules
                        require(members.putIfAbsent(memberName, linkedMapOf("member" to memberName, "memberTypeName" to runtimeType,
                            "rules" to normalized.map { linkedMapOf("ruleName" to it.ruleName, "arguments" to it.arguments, "message" to it.message) })) == null) { "duplicate member '$memberName'" }
                    }
                    val canonical = linkedMapOf("validatorTypeName" to name, "modelTypeName" to model, "members" to members.values.toList())
                    require(local.putIfAbsent(name, canonical) == null) { "duplicate validator '$name'" }
                    val node = mapper.readTree(mapper.writeValueAsString(canonical))
                    val previous = validators.putIfAbsent(name, node)
                    require(previous == null || previous == node) { "conflicting fluent declaration '$name'" }
                }
                val canonical = mapper.writeValueAsString(linkedMapOf("formatVersion" to 1, "moduleName" to module.asString(), "validators" to local.values.toList()))
                val previous = modules.putIfAbsent(module.asString(), canonical)
                require(previous == null || previous == canonical) { "conflicting fluent module '${module.asString()}'" }
            } catch (exception: Exception) {
                throw GradleException("Invalid shared fluent metadata '$origin': ${exception.message}; rebuild the producer with Arc KSP.", exception)
            }
        }
        modules.keys.forEach { module ->
            val generated = "io.cratis.arc.generated.${module}ArcArtifactModule"
            requireMetadata(generated in moduleClasses && generated in serviceModules,
                "Fluent module '$module' is missing its compiled runtime artifact module or ServiceLoader registration")
            val references = moduleClasses.getValue(generated)
            val local = mapper.readTree(modules.getValue(module)).path("validators").toList().map { it.path("validatorTypeName").asString() }
            requireMetadata("io/cratis/arc/validation/FluentValidatorRegistration" in references && local.all { it.replace('.', '/') in references },
                "Fluent module '$module' has no generated runtime validator linkage; rebuild its module and declarations together")
        }
        requireMetadata(classes == validators.keys,
            "Shared fluent compiler metadata inventory mismatch; unindexed=${classes - validators.keys}, missing bytecode=${validators.keys - classes}")
        val scopes = sortedMapOf<String, Scope>()
        for ((resource, origin, bytes) in scopeResources) {
            try {
                val node = mapper.readTree(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
                fields(node, setOf("formatVersion", "moduleName", "indexed", "validators"))
                val version = node.path("formatVersion")
                require(version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1) { "expected scope formatVersion=1" }
                val module = string(node.path("moduleName"))
                require(safeModuleName(module) && resource == "$SCOPE_PREFIX$module.json") { "scope resource must agree with a safe moduleName" }
                require(node.path("indexed").isBoolean && node.path("validators").isArray) { "scope must declare indexed boolean and validators array" }
                val names = node.path("validators").toList().map(::type)
                require(names.distinct().size == names.size && names.all { it in validators }) { "scope references duplicate or missing validator declarations" }
                val scope = Scope(node.path("indexed").asBoolean(), names.toSortedSet())
                require(scope.indexed || scope.validators.isEmpty()) { "unindexed scope cannot claim shared declarations" }
                if (scope.validators.isNotEmpty()) {
                    val generated = "io.cratis.arc.generated.${module}ArcArtifactModule"
                    val references = moduleClasses[generated].orEmpty()
                    require(generated in serviceModules && "io/cratis/arc/validation/FluentValidatorRegistration" in references &&
                        scope.validators.all { it.replace('.', '/') in references }) { "scope has no matching generated runtime validator linkage" }
                }
                val previous = scopes.putIfAbsent(module, scope)
                require(previous == null || previous == scope) { "conflicting scope for '$module'" }
            } catch (exception: Exception) {
                throw GradleException("Invalid fluent compiler scope '$origin': ${exception.message}; rebuild the producer.", exception)
            }
        }
        return Inventory(modules, validators, classes, scopes)
    }

    internal fun normalizeMember(name: String, runtimeType: String, rules: List<ValidationRuleDescriptor>): List<ValidationRuleDescriptor> =
        try { FluentValidationMember(name, validationType(runtimeType), rules).rules } catch (exception: IllegalArgumentException) {
            throw GradleException("[ARCVALIDATION_GRAPH] Invalid shared conjunction for '$name': ${exception.message}", exception)
        }

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

    private data class Header(val name: String, val parent: String?, val references: Set<String>)

    private fun classHeader(bytes: ByteArray): Header = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 0xCAFEBABE.toInt()) { "invalid class magic" }
        input.readUnsignedShort(); input.readUnsignedShort()
        val pool = arrayOfNulls<Any>(input.readUnsignedShort())
        var index = 1
        while (index < pool.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> pool[index] = input.readUTF()
                7 -> pool[index] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipNBytes(2)
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                5, 6 -> { input.skipNBytes(8); index++ }
                15 -> input.skipNBytes(3)
                else -> error("invalid constant-pool tag $tag")
            }
            index++
        }
        input.readUnsignedShort()
        fun className(index: Int): String = pool[pool[index] as Int] as String
        val name = className(input.readUnsignedShort())
        val parent = input.readUnsignedShort()
        Header(name, if (parent == 0) null else className(parent), pool.filterIsInstance<Int>().map { pool[it] as String }.toSet())
    }
    private fun string(node: JsonNode): String {
        require(node.isString) { "expected a string" }
        return node.asString()
    }
    private fun type(node: JsonNode): String {
        require(node.isString && typeName.matches(node.asString())) { "use canonical public type names" }
        return node.asString()
    }
    private fun fields(node: JsonNode, expected: Set<String>) {
        require(node.isObject && node.properties().map { it.key }.toSet() == expected) { "expected exactly $expected" }
    }
    private fun requireMetadata(condition: Boolean, message: String) {
        if (!condition) throw GradleException("$message; use matching Arc-processed compile/runtime dependencies.")
    }
    // Use the established handler reader's exact module-name validator, without importing compiler code.
    private fun safeModuleName(name: String): Boolean = ArcResponseHandlerMetadataDiscovery.isSafeModuleName(name)
}
