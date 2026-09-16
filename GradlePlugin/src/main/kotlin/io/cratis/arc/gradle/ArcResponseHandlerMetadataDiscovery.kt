// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import java.io.File
import java.nio.ByteBuffer
import java.util.jar.JarFile
import org.gradle.api.GradleException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Reads only the separate format-1 declaration namespace, never artifact manifests. */
internal object ArcResponseHandlerMetadataDiscovery {
    private const val PREFIX = "META-INF/cratis/arc-response-handlers/"
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    // Match KSP Naming.validateModuleName exactly; both readers use the shared contract test cases.
    // Keep this internal rather than adding a compiler dependency or a public runtime naming API.
    private val kotlinKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic", "field",
        "file", "finally", "get", "import", "init", "param", "property", "receiver", "set", "setparam", "where",
        "actual", "abstract", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
        "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator",
        "out", "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg"
    )
    private val typeName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    internal fun isSafeModuleName(name: String): Boolean = identifier.matches(name) && name !in kotlinKeywords

    fun extract(artifacts: Iterable<File>): ByteArray {
        val documents = mutableListOf<Pair<String, JsonNode>>()
        artifacts.forEach { file ->
            when {
                file.isDirectory -> file.resolve(PREFIX).takeIf(File::isDirectory)?.walkTopDown()
                    ?.filter { it.isFile && it.extension == "json" }?.forEach {
                        documents += it.relativeTo(file).invariantSeparatorsPath to read(it.readBytes(), it.path)
                    }
                file.isFile && file.extension.equals("jar", true) -> JarFile(file).use { jar ->
                    jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(PREFIX) && it.name.endsWith(".json") }
                        .forEach { entry ->
                            documents += entry.name to jar.getInputStream(entry).use { read(it.readBytes(), "${file.path}!/${entry.name}") }
                        }
                }
                !file.exists() -> throw GradleException("Missing Arc response handler dependency artifact: $file")
            }
        }
        val modules = sortedMapOf<String, String>()
        val handlers = sortedMapOf<String, Pair<List<String>, String>>()
        documents.sortedBy { it.first }.forEach { (resource, root) ->
            try {
                fields(root, setOf("formatVersion", "moduleName", "handlers"))
                val version = root.path("formatVersion")
                require(version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1) { "expected formatVersion=1" }
                val name = root.path("moduleName")
                require(name.isString && isSafeModuleName(name.stringValue())) {
                    "moduleName must be a safe identifier"
                }
                val module = name.stringValue()
                require(resource == "$PREFIX$module.json") { "resource name must agree with moduleName" }
                val entries = root.path("handlers")
                require(entries.isArray) { "handlers must be an array" }
                val local = sortedMapOf<String, List<String>>()
                entries.forEach { entry ->
                    fields(entry, setOf("handlerTypeName", "handledTypeNames"))
                    val handler = type(entry.path("handlerTypeName"))
                    val values = entry.path("handledTypeNames")
                    require(values.isArray && !values.isEmpty) { "handledTypeNames must be a nonempty array" }
                    val names = values.toList().map(::type)
                    require(names.distinct().size == names.size) { "duplicate handled type names" }
                    require(local.putIfAbsent(handler, names.sorted()) == null) { "duplicate handler '$handler'" }
                }
                val canonical = "{\"formatVersion\":1,\"moduleName\":\"$module\",\"handlers\":[" +
                    local.entries.joinToString(",") { (handler, values) ->
                        "{\"handlerTypeName\":\"$handler\",\"handledTypeNames\":[" +
                            values.joinToString(",") { "\"$it\"" } + "]}"
                    } + "]}"
                val previous = modules.putIfAbsent(module, canonical)
                require(previous == null || previous == canonical) { "conflicting module documents for '$module'" }
                local.forEach { (handler, values) ->
                    val prior = handlers.putIfAbsent(handler, values to resource)
                    require(prior == null || prior.first == values) { "conflicting handler '$handler' in ${prior?.second} and $resource" }
                }
            } catch (exception: IllegalArgumentException) {
                throw GradleException("Invalid Arc response handler metadata in $resource: ${exception.message}; rebuild the producer.", exception)
            }
        }
        return ("{\"formatVersion\":1,\"modules\":[${modules.values.joinToString(",") }]}\n").toByteArray(Charsets.UTF_8)
    }

    private fun read(bytes: ByteArray, source: String): JsonNode = try {
        mapper.readTree(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
    } catch (exception: Exception) {
        throw GradleException("Invalid Arc response handler metadata in $source; rebuild the producer.", exception)
    }

    private fun type(node: JsonNode): String {
        require(node.isString && typeName.matches(node.stringValue())) { "type names must be canonical public top-level names" }
        return node.stringValue()
    }

    private fun fields(node: JsonNode, expected: Set<String>) {
        require(node.isObject && node.properties().map { it.key }.toSet() == expected) { "expected exactly $expected" }
    }
}
