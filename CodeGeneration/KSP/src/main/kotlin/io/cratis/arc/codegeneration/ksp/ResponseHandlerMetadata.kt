// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Separate compiler transport, deliberately not part of ArcArtifactManifest. */
internal object ResponseHandlerMetadata {
    const val OPTION = "arc.responseHandlerMetadata"
    const val PREFIX = "META-INF/cratis/arc-response-handlers/"
    private val typeName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val mapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    data class Declaration(val handler: String, val values: List<String>, val resource: String)

    fun read(option: String): List<Declaration> {
        val uri = URI(option)
        require(uri.isAbsolute && uri.scheme == "file" && uri.fragment == null && uri.query == null) {
            "supply an absolute file URI"
        }
        val file = File(uri)
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.readBytes())).toString()
        val root = mapper.readTree(text)
        fields(root, setOf("formatVersion", "modules"))
        version(root)
        val modules = root.path("modules")
        require(modules.isArray) { "modules must be an array" }
        val documents = sortedMapOf<String, List<Declaration>>()
        val declarations = sortedMapOf<String, Declaration>()
        modules.toList().sortedBy { it.path("moduleName").toString() }.forEach { module ->
            fields(module, setOf("formatVersion", "moduleName", "handlers"))
            version(module)
            val name = module.path("moduleName")
            require(name.isString && validateModuleName(name.stringValue()) != null) { "moduleName must be a safe module identifier" }
            val resource = "$PREFIX${name.stringValue()}.json"
            val handlers = module.path("handlers")
            require(handlers.isArray) { "$resource handlers must be an array" }
            val local = sortedMapOf<String, Declaration>()
            handlers.forEach { handler ->
                fields(handler, setOf("handlerTypeName", "handledTypeNames"))
                val handlerName = name(handler.path("handlerTypeName"))
                val values = handler.path("handledTypeNames")
                require(values.isArray && !values.isEmpty) { "$resource handledTypeNames must be a nonempty array" }
                val names = values.toList().map(::name)
                require(names.distinct().size == names.size) { "$resource contains duplicate handled type names" }
                val declaration = Declaration(handlerName, names.sorted(), resource)
                require(local.putIfAbsent(handlerName, declaration) == null) { "$resource contains duplicate handler '$handlerName'" }
            }
            val canonical = local.values.toList()
            val previous = documents.putIfAbsent(name.stringValue(), canonical)
            require(previous == null || previous == canonical) { "Conflicting module documents for $resource; rebuild the producer" }
            canonical.forEach { declaration ->
                val previousHandler = declarations.putIfAbsent(declaration.handler, declaration)
                require(previousHandler == null || previousHandler.values == declaration.values) {
                    "Conflicting handler '${declaration.handler}' in ${previousHandler?.resource} and $resource; rebuild the producers"
                }
            }
        }
        return declarations.values.toList()
    }

    fun document(moduleName: String, handlers: Map<String, List<String>>): String =
        "{\"formatVersion\":1,\"moduleName\":\"$moduleName\",\"handlers\":[" +
            handlers.toSortedMap().entries.joinToString(",") { (handler, values) ->
                "{\"handlerTypeName\":\"$handler\",\"handledTypeNames\":[" +
                    values.sorted().joinToString(",") { "\"$it\"" } + "]}"
            } + "]}\n"

    fun exportableName(name: String): Boolean = typeName.matches(name)

    private fun name(node: JsonNode): String {
        require(node.isString && typeName.matches(node.stringValue())) { "type names must be canonical public top-level names" }
        return node.stringValue()
    }

    private fun version(node: JsonNode) {
        val version = node.path("formatVersion")
        require(version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1) { "expected formatVersion=1" }
    }

    private fun fields(node: JsonNode, expected: Set<String>) {
        require(node.isObject && node.properties().map { it.key }.toSet() == expected) { "expected exactly $expected" }
    }
}
