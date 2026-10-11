// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

// Type names, annotation names and lookup tables used when collecting model metadata.

internal const val CONCEPT_AS_TYPE = "io.cratis.arc.concepts.ConceptAs"
internal const val ARC_ENUM_TYPE = "io.cratis.arc.concepts.ArcEnum"
internal const val ARC_ENUM_VALUE_ANNOTATION = "io.cratis.arc.concepts.ArcEnumValue"
internal const val FLAGS_ANNOTATION = "io.cratis.arc.concepts.Flags"
internal const val JSON_IGNORE_ANNOTATION = "com.fasterxml.jackson.annotation.JsonIgnore"
internal const val JSON_PROPERTY_ANNOTATION = "com.fasterxml.jackson.annotation.JsonProperty"
internal const val DERIVED_TYPE_ANNOTATION = "io.cratis.arc.polymorphism.DerivedType"
internal val JAVA_COLLECTION_NAMES = setOf("List", "Collection", "java.util.List", "java.util.Collection")
internal val JAVA_MAP_NAMES = setOf("Map", "java.util.Map")
internal val JAVA_TYPE_ALIASES = mapOf(
    "boolean" to "java.lang.Boolean",
    "byte" to "java.lang.Byte",
    "char" to "java.lang.Character",
    "double" to "java.lang.Double",
    "float" to "java.lang.Float",
    "int" to "java.lang.Integer",
    "long" to "java.lang.Long",
    "short" to "java.lang.Short"
)
internal val KOTLIN_TO_JAVA_BOXED_TYPE_NAMES = mapOf(
    "kotlin.Boolean" to "java.lang.Boolean",
    "kotlin.Byte" to "java.lang.Byte",
    "kotlin.Char" to "java.lang.Character",
    "kotlin.Double" to "java.lang.Double",
    "kotlin.Float" to "java.lang.Float",
    "kotlin.Int" to "java.lang.Integer",
    "kotlin.Long" to "java.lang.Long",
    "kotlin.Short" to "java.lang.Short",
    "kotlin.String" to "java.lang.String"
)
internal val ROOT_TYPE_NAMES = setOf(
    "kotlin.Any",
    "java.lang.Object",
    "java.lang.Record",
    "java.io.Serializable",
    "java.lang.Cloneable"
)
internal val INTEGER_TYPE_NAMES = setOf("kotlin.Int", "java.lang.Integer", "int")
internal val STRING_TYPE_NAMES = setOf("kotlin.String", "java.lang.String")
internal val MAP_SAFE_PRIMITIVE_TYPE_NAMES = setOf(
    "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Int", "kotlin.Short", "kotlin.String",
    "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Integer", "java.lang.Short",
    "java.lang.String", "boolean", "byte", "char", "int", "short"
)
internal val TERMINAL_TYPE_NAMES = setOf(
    "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Double", "kotlin.Float", "kotlin.Int",
    "kotlin.Long", "kotlin.Short", "kotlin.String", "java.lang.Boolean", "java.lang.Byte",
    "java.lang.Character", "java.lang.Double", "java.lang.Float", "java.lang.Integer", "java.lang.Long",
    "java.lang.Short", "java.lang.String", "boolean", "byte", "char", "double", "float", "int",
    "long", "short", "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID", "java.util.Date",
    "java.time.Duration", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
    "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.Period",
    "java.time.Year", "java.time.YearMonth", "java.time.ZonedDateTime"
)
