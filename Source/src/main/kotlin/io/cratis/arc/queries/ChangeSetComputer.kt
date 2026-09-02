// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.results.ChangeSet
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Computes deterministic collection deltas using a stable item identity. */
public class ChangeSetComputer @JvmOverloads constructor(
    private val objectMapper: ObjectMapper = ArcObjectMapper.create(),
    private val generatedKeyExtractors: Map<Class<*>, (Any) -> Any?> = emptyMap()
) {
    private val accessors = ConcurrentHashMap<Class<*>, KeyAccessor>()

    /** Computes a change set, or returns `null` when stable identity cannot be established. */
    @JvmOverloads
    public fun compute(
        previous: List<*>?,
        current: List<*>,
        keyExtractor: ((Any) -> Any?)? = null
    ): ChangeSet<Any?>? {
        if (previous == null) return ChangeSet(added = current)
        val sample = current.firstOrNull() ?: previous.firstOrNull()
        if (sample == null) return ChangeSet()
        val extractor = keyExtractor ?: generatedKeyExtractors[sample.javaClass] ?: accessorFor(sample.javaClass).extractor
            ?: return null

        val previousByKey = LinkedHashMap<Any, Any?>()
        previous.forEach { item -> item?.let { extractor(it)?.let { key -> previousByKey[key] = it } } }
        val currentByKey = LinkedHashMap<Any, Any?>()
        current.forEach { item -> item?.let { extractor(it)?.let { key -> currentByKey[key] = it } } }
        if (previousByKey.size != previous.count { it != null } || currentByKey.size != current.count { it != null }) {
            return null
        }

        val added = mutableListOf<Any?>()
        val replaced = mutableListOf<Any?>()
        currentByKey.forEach { (key, item) ->
            val old = previousByKey[key]
            if (!previousByKey.containsKey(key)) {
                added.add(item)
            } else if (objectMapper.writeValueAsBytes(old).contentEquals(objectMapper.writeValueAsBytes(item)).not()) {
                replaced.add(item)
            }
        }
        val removed = previousByKey.filterKeys { it !in currentByKey }.values.toList()
        return ChangeSet(added, replaced, removed)
    }

    private fun accessorFor(type: Class<*>): KeyAccessor = accessors.computeIfAbsent(type) { discoverAccessor(it) }

    private fun discoverAccessor(type: Class<*>): KeyAccessor {
        val method = type.methods.firstOrNull(::isCommandKey) ?: type.methods.firstOrNull {
            it.parameterCount == 0 && (it.name.equals("id", true) || it.name.equals("getId", true))
        }
        return KeyAccessor(method?.let { keyMethod -> { instance: Any -> keyMethod.invoke(instance) } })
    }

    private fun isCommandKey(method: Method): Boolean = method.parameterCount == 0 &&
        (method.isAnnotationPresent(CommandKey::class.java) ||
            method.declaringClass.declaredFields.any { field ->
                field.name == method.name.removePrefix("get").replaceFirstChar(Char::lowercase) &&
                    field.isAnnotationPresent(CommandKey::class.java)
            })

    private class KeyAccessor(val extractor: ((Any) -> Any?)?)
}
