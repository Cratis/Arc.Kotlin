// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.json.ArcObjectMapper
import org.gradle.api.GradleException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** Duplicate definitions must agree structurally; validation is a conjunction, never first/last wins. */
internal object ValidationDescriptorMerge {
    private val mapper = ArcObjectMapper.create()
    private val order = listOf("notNull", "notEmpty", "minLength", "maxLength", "length", "emailAddress", "phone", "url", "creditCard", "matches",
        "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual")

    fun <T : Any> merge(values: List<T>, type: Class<T>, identity: String): T {
        if (values.size == 1) return values.single()
        val nodes = values.map { mapper.readTree(mapper.writeValueAsString(it)) as ObjectNode }
        fun structure(node: ObjectNode): JsonNode = node.deepCopy().also { copy ->
            copy.path("properties").forEach { property ->
                (property as ObjectNode).remove("validationRules")
                property.remove("validateRecursively")
            }
        }
        val shape = structure(nodes.first())
        if (nodes.drop(1).any { structure(it) != shape }) throw GradleException(
            "Incompatible Arc descriptor structures for '$identity'; rebuild the producers instead of selecting one definition.")
        val result = nodes.first().deepCopy()
        result.path("properties").forEachIndexed { index, property ->
            val definitions = nodes.map { it.path("properties").get(index) }
            val rules = definitions.flatMap { it.path("validationRules").toList() }.distinctBy { rule ->
                listOf(rule.path("ruleName").asString(), rule.path("arguments").toList().map {
                    if (it.isNumber) it.asString().toBigDecimal().stripTrailingZeros().toPlainString() else it.asString()
                }, rule.path("message").takeUnless { it.isNull || it.isMissingNode }?.asString())
            }.sortedWith(compareBy<JsonNode> { order.indexOf(it.path("ruleName").asString()) }
                .thenBy { it.path("arguments").toString() }.thenBy { it.path("message").toString() })
            val target = property as ObjectNode
            val array = target.putArray("validationRules")
            rules.forEach(array::add)
            target.put("validateRecursively", definitions.any { it.path("validateRecursively").asBoolean() })
        }
        return mapper.treeToValue(result, type)
    }
}
