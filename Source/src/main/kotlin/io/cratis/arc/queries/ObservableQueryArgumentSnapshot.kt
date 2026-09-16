// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.concepts.ConceptAs
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.MonthDay
import java.time.ZonedDateTime
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import tools.jackson.databind.ObjectMapper

/** Bounded per-dispatch value capture, not an arbitrary model or polymorphic cloning facility. */
internal class ObservableQueryArgumentSnapshot(private val mapper: ObjectMapper, private val coroutineContext: CoroutineContext) {
    private sealed interface Node
    private class Scalar(val value: Any?) : Node
    private class Sequence(val component: Class<*>?, val values: List<Node>) : Node
    private class Mapping(val values: List<Pair<String, Node>>) : Node
    private class Concept(val type: Class<*>, val scalar: Any, val json: String) : Node

    private val ancestors = IdentityHashMap<Any, Boolean>()
    // Original references are used only for identity rejection, never as materialization fallbacks.
    private val conceptInstances = IdentityHashMap<Any, Boolean>()
    private var captured = 0
    private var reconstructed = 0

    fun copies(arguments: Map<String, Any?>, count: Int): List<Map<String, Any?>> {
        val snapshot = capture(arguments, 0) as Mapping
        require(count.toLong() + 1 <= 100_000L / captured) { "Observable guard arguments exceed the reconstruction budget." }
        // A discarded probe also catches a constant/cache-returning codec when there is just one guard.
        materializeMap(snapshot)
        return List(count) { materializeMap(snapshot) }
    }

    private fun capture(value: Any?, depth: Int): Node {
        coroutineContext.ensureActive()
        require(depth <= 64 && ++captured <= 10_000) { "Observable guard arguments exceed the capture budget." }
        if (value == null || value.javaClass in scalarTypes) return Scalar(value)
        require(ancestors.put(value, true) == null) { "Cyclic observable guard argument." }
        try {
            return when {
                value is Enum<*> -> captureEnum(value)
                value is ConceptAs<*> -> captureConcept(value)
                value.javaClass.isArray -> {
                    val length = java.lang.reflect.Array.getLength(value)
                    require(length <= 10_000 - captured) { "Observable guard array exceeds the capture budget." }
                    Sequence(value.javaClass.componentType, List(length) { capture(java.lang.reflect.Array.get(value, it), depth + 1) })
                }
                value is List<*> -> {
                    require(value.size <= 10_000 - captured) { "Observable guard list exceeds the capture budget." }
                    Sequence(null, value.map { capture(it, depth + 1) })
                }
                value is Map<*, *> -> {
                    require(value.size <= 10_000 - captured) { "Observable guard map exceeds the capture budget." }
                    Mapping(value.entries.map {
                        require(it.key is String) { "Observable guard argument maps require string keys." }
                        Pair(it.key as String, capture(it.value, depth + 1))
                    })
                }
                else -> error("Unsupported observable guard argument type '${value.javaClass.name}'.")
            }
        } finally {
            ancestors.remove(value)
        }
    }

    private fun captureEnum(value: Enum<*>): Scalar {
        // Enum identity also recognizes constant-specific subclasses. Enum's own name/ordinal are immutable;
        // every application instance field, including subclass, synthetic and transient state, must be safe.
        // Inspect declared types only: never read/reset fields, clone constants, or execute application getters.
        generateSequence<Class<*>>(value.javaClass) { it.superclass }
            .takeWhile { it != Enum::class.java }
            .forEach { type ->
                coroutineContext.ensureActive()
                require(type.declaredFields.all { field ->
                    Modifier.isStatic(field.modifiers) ||
                        (Modifier.isFinal(field.modifiers) && (field.type.isPrimitive ||
                            (field.type in scalarTypes && Modifier.isFinal(field.type.modifiers))))
                }) { "Observable guard enums require only final primitive or exact final immutable scalar instance fields." }
            }
        // Static/global state and arbitrary method side effects remain application-owned, not an isolation promise.
        return Scalar(value)
    }

    private fun captureConcept(value: ConceptAs<*>): Concept {
        val type: Class<*> = value.javaClass
        require(Modifier.isFinal(type.modifiers) && !type.isAnonymousClass && !type.isLocalClass && type.typeParameters.isEmpty()) {
            "Observable guard concepts must be concrete final nongeneric scalar types."
        }
        val fields = generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }.toList()
        val scalar = requireNotNull(value.value()) { "Observable guard concepts require a non-null scalar." }
        require(scalar.javaClass in scalarTypes && fields.size == 1 &&
            boxed(fields.single().type) == scalar.javaClass) { "Observable guard concepts require only one scalar field and no extra state." }
        val json = mapper.writeValueAsString(value)
        coroutineContext.ensureActive()
        val tree = mapper.readTree(json)
        require(tree.isValueNode && !tree.isNull && !tree.isMissingNode) { "Observable guard concept JSON must be scalar." }
        require(sameScalar(scalar, value.value())) { "Observable guard concept serialization changed its value." }
        conceptInstances[value] = true
        return Concept(type, scalar, json)
    }

    private fun materialize(node: Node): Any? {
        coroutineContext.ensureActive()
        require(++reconstructed <= 100_000) { "Observable guard arguments exceed the reconstruction budget." }
        return when (node) {
            is Scalar -> node.value
            is Mapping -> mapEntries(node)
            is Sequence -> if (node.component == null) {
                node.values.mapTo(ArrayList()) { materialize(it) }
            } else {
                // Array allocation only: component assignability is checked by Array.set, before any guard runs.
                java.lang.reflect.Array.newInstance(node.component, node.values.size).also { array ->
                    node.values.forEachIndexed { index, child -> java.lang.reflect.Array.set(array, index, materialize(child)) }
                }
            }
            is Concept -> {
                val copy = mapper.readValue(node.json, node.type)
                coroutineContext.ensureActive()
                require(copy != null && copy.javaClass == node.type && copy is ConceptAs<*> &&
                    conceptInstances.put(copy, true) == null && sameScalar(node.scalar, copy.value())) {
                    "Observable guard concept codec must preserve type and value and return fresh instances."
                }
                require(mapper.writeValueAsString(copy) == node.json && sameScalar(node.scalar, copy.value())) {
                    "Observable guard concept codec must preserve scalar JSON and value."
                }
                coroutineContext.ensureActive()
                copy
            }
        }
    }

    private fun materializeMap(node: Mapping): Map<String, Any?> {
        coroutineContext.ensureActive()
        require(++reconstructed <= 100_000) { "Observable guard arguments exceed the reconstruction budget." }
        return mapEntries(node)
    }

    private fun mapEntries(node: Mapping): Map<String, Any?> = LinkedHashMap<String, Any?>().also { map ->
        node.values.forEach { (key, value) -> map[key] = materialize(value) }
    }

    private fun sameScalar(expected: Any, actual: Any?): Boolean =
        actual != null && expected.javaClass == actual.javaClass && expected == actual

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        else -> type
    }

    private companion object {
        val scalarTypes: Set<Class<*>> = setOf(
            String::class.java, Boolean::class.javaObjectType, Byte::class.javaObjectType, Short::class.javaObjectType,
            Int::class.javaObjectType, Long::class.javaObjectType, Float::class.javaObjectType,
            Double::class.javaObjectType, Char::class.javaObjectType, BigInteger::class.java, BigDecimal::class.java,
            UUID::class.java, Instant::class.java, LocalDate::class.java, LocalTime::class.java, LocalDateTime::class.java,
            OffsetDateTime::class.java, OffsetTime::class.java, ZonedDateTime::class.java, Duration::class.java,
            Period::class.java, Year::class.java, YearMonth::class.java, MonthDay::class.java
        )
    }
}
