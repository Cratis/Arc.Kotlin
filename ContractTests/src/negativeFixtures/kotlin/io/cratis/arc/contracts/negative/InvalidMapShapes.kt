// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.polymorphism.DerivedType
import io.cratis.arc.queries.QueryHttpMethod
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

public data class InvalidMapModelLeaf(public val value: String)

public enum class InvalidMapEnumLeaf {
    Value
}

@DerivedType("invalid-map-derived")
public data class InvalidMapDerivedLeaf(public val value: String)

public data class InvalidMapConcept(private val raw: Map<String, String>) : ConceptAs<Map<String, String>> {
    override fun value(): Map<String, String> = raw
}

@Command
public data class NullableMapKey(public val values: Map<String?, String>) {
    public fun handle(): Unit = Unit
}

@Command
public data class NonStringMapKey(public val values: Map<Int, String>) {
    public fun handle(): Unit = Unit
}

@Command
public data class NullableDirectMapValue(public val values: Map<String, String?>) {
    public fun handle(): Unit = Unit
}

@Command
public data class NullableMapSequenceElement(public val values: Map<String, List<Int?>>) {
    public fun handle(): Unit = Unit
}

@Command
public data class StarProjectedMap(public val values: Map<*, String>) {
    public fun handle(): Unit = Unit
}

@Command
public data class ModelMapLeaf(public val values: Map<String, InvalidMapModelLeaf>) {
    public fun handle(): Unit = Unit
}

@Command
public data class ConceptMapLeaf(public val values: Map<String, InvalidMapConcept>) {
    public fun handle(): Unit = Unit
}

@Command
public data class EnumMapLeaf(public val values: Map<String, InvalidMapEnumLeaf>) {
    public fun handle(): Unit = Unit
}

@Command
public data class UnsafeLongMapLeaf(public val values: Map<String, Long>) {
    public fun handle(): Unit = Unit
}

@Command
public data class UnsafeFloatMapLeaf(public val values: Map<String, Float>) {
    public fun handle(): Unit = Unit
}

@Command
public data class UnsafeDoubleMapLeaf(public val values: Map<String, Double>) {
    public fun handle(): Unit = Unit
}

@Command
public data class UuidMapLeaf(public val values: Map<String, UUID>) {
    public fun handle(): Unit = Unit
}

@Command
public data class TemporalMapLeaf(public val values: Map<String, Instant>) {
    public fun handle(): Unit = Unit
}

@Command
public data class DerivedMapLeaf(public val values: Map<String, InvalidMapDerivedLeaf>) {
    public fun handle(): Unit = Unit
}

@Command
public data class ConceptAsMapProperty(public val value: InvalidMapConcept) {
    public fun handle(): Unit = Unit
}

@Command
public class TopLevelMapResponse {
    public fun handle(): Map<String, String> = emptyMap()
}

@Command
public class MapServiceParameter {
    public fun handle(values: Map<String, String>): Unit = Unit
}

@ReadModel
public data class InvalidMapQueries(public val value: String) {
    public companion object {
        public fun mapParameter(values: Map<String, String>): InvalidMapQueries =
            InvalidMapQueries(values.size.toString())

        public fun mapService(@FromServices values: Map<String, String>): InvalidMapQueries =
            InvalidMapQueries(values.size.toString())

        public fun observableMapParameter(values: Flow<Map<String, String>>): InvalidMapQueries =
            InvalidMapQueries(values.toString())

        @QueryHttpMethod
        public fun mapReturn(): Map<String, String> = emptyMap()

        @QueryHttpMethod
        public fun observableMapReturn(): Flow<Map<String, String>> = error("not invoked")
    }
}
