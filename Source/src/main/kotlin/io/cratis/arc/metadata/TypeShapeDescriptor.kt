// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/** The structural kind represented by a [TypeShapeDescriptor]. */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum class TypeShapeKind {
    /** A named non-container value. */
    VALUE,

    /** An ordered array or collection of values. */
    SEQUENCE,

    /** A JSON object whose property names encode permitted map keys. */
    MAP
}

/** The runtime container category represented by a sequence shape. */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum class SequenceKind {
    /** A JVM array. */
    ARRAY,

    /** A list implementation. */
    LIST,

    /** A collection whose more specific implementation is not part of the contract. */
    COLLECTION
}

/** The codec used to represent a map key as a JSON object property name. */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum class MapKeyCodec {
    /** A non-reserved string key is used verbatim as the JSON property name. */
    STRING
}

/**
 * Immutable recursive metadata describing one value, sequence, or map type shape.
 *
 * The public constructor is intentionally explicit for Jackson and Java callers. Prefer the static factories when
 * constructing descriptors in application code.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder(
    "kind",
    "nullable",
    "typeName",
    "sequenceKind",
    "elementShape",
    "keyShape",
    "valueShape",
    "keyCodec"
)
public class TypeShapeDescriptor @JsonCreator constructor(
    @JsonProperty("kind") public val kind: TypeShapeKind,
    @JsonProperty("nullable") public val nullable: Boolean,
    @JsonProperty("typeName") public val typeName: String?,
    @JsonProperty("sequenceKind") public val sequenceKind: SequenceKind?,
    @JsonProperty("elementShape") public val elementShape: TypeShapeDescriptor?,
    @JsonProperty("keyShape") public val keyShape: TypeShapeDescriptor?,
    @JsonProperty("valueShape") public val valueShape: TypeShapeDescriptor?,
    @JsonProperty("keyCodec") public val keyCodec: MapKeyCodec?
) {
    init {
        when (kind) {
            TypeShapeKind.VALUE -> {
                require(!typeName.isNullOrBlank()) { "A VALUE shape requires a non-blank typeName." }
                require(sequenceKind == null && elementShape == null) {
                    "A VALUE shape cannot declare sequence metadata."
                }
                require(keyShape == null && valueShape == null && keyCodec == null) {
                    "A VALUE shape cannot declare map metadata."
                }
            }

            TypeShapeKind.SEQUENCE -> {
                require(typeName == null) { "A SEQUENCE shape cannot declare typeName." }
                require(sequenceKind != null && elementShape != null) {
                    "A SEQUENCE shape requires sequenceKind and elementShape."
                }
                require(keyShape == null && valueShape == null && keyCodec == null) {
                    "A SEQUENCE shape cannot declare map metadata."
                }
            }

            TypeShapeKind.MAP -> {
                require(typeName == null) { "A MAP shape cannot declare typeName." }
                require(sequenceKind == null && elementShape == null) {
                    "A MAP shape cannot declare sequence metadata."
                }
                require(keyShape != null && valueShape != null && keyCodec != null) {
                    "A MAP shape requires keyShape, valueShape, and keyCodec."
                }
                require(!keyShape.nullable) { "A MAP key shape cannot be nullable." }
                require(keyShape.kind == TypeShapeKind.VALUE) { "A MAP key shape must be a VALUE shape." }
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is TypeShapeDescriptor &&
        kind == other.kind && nullable == other.nullable && typeName == other.typeName &&
        sequenceKind == other.sequenceKind && elementShape == other.elementShape &&
        keyShape == other.keyShape && valueShape == other.valueShape && keyCodec == other.keyCodec

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + nullable.hashCode()
        result = 31 * result + (typeName?.hashCode() ?: 0)
        result = 31 * result + (sequenceKind?.hashCode() ?: 0)
        result = 31 * result + (elementShape?.hashCode() ?: 0)
        result = 31 * result + (keyShape?.hashCode() ?: 0)
        result = 31 * result + (valueShape?.hashCode() ?: 0)
        return 31 * result + (keyCodec?.hashCode() ?: 0)
    }

    override fun toString(): String =
        "TypeShapeDescriptor(kind=$kind, nullable=$nullable, typeName=$typeName, sequenceKind=$sequenceKind, " +
            "elementShape=$elementShape, keyShape=$keyShape, valueShape=$valueShape, keyCodec=$keyCodec)"

    public companion object {
        /** Creates a named value shape. */
        @JvmStatic
        @JvmOverloads
        public fun value(typeName: String, nullable: Boolean = false): TypeShapeDescriptor = TypeShapeDescriptor(
            TypeShapeKind.VALUE,
            nullable,
            typeName,
            null,
            null,
            null,
            null,
            null
        )

        /** Creates an array or collection shape. */
        @JvmStatic
        @JvmOverloads
        public fun sequence(
            sequenceKind: SequenceKind,
            elementShape: TypeShapeDescriptor,
            nullable: Boolean = false
        ): TypeShapeDescriptor = TypeShapeDescriptor(
            TypeShapeKind.SEQUENCE,
            nullable,
            null,
            sequenceKind,
            elementShape,
            null,
            null,
            null
        )

        /** Creates a map shape whose wire keys are non-reserved strings. */
        @JvmStatic
        @JvmOverloads
        public fun map(
            keyShape: TypeShapeDescriptor,
            valueShape: TypeShapeDescriptor,
            keyCodec: MapKeyCodec = MapKeyCodec.STRING,
            nullable: Boolean = false
        ): TypeShapeDescriptor = TypeShapeDescriptor(
            TypeShapeKind.MAP,
            nullable,
            null,
            null,
            null,
            keyShape,
            valueShape,
            keyCodec
        )
    }
}

internal fun legacyPropertyShape(
    typeName: String,
    nullable: Boolean,
    enumerable: Boolean,
    elementTypeName: String?
): TypeShapeDescriptor = if (enumerable) {
    TypeShapeDescriptor.sequence(
        sequenceKindFor(typeName),
        TypeShapeDescriptor.value(elementTypeName ?: "java.lang.Object"),
        nullable
    )
} else {
    TypeShapeDescriptor.value(typeName, nullable)
}

internal fun legacyLeafShape(
    typeName: String,
    nullable: Boolean,
    enumerable: Boolean
): TypeShapeDescriptor = if (enumerable) {
    TypeShapeDescriptor.sequence(SequenceKind.COLLECTION, TypeShapeDescriptor.value(typeName), nullable)
} else {
    TypeShapeDescriptor.value(typeName, nullable)
}

internal fun TypeShapeDescriptor.compatibilityTypeName(): String = when (kind) {
    TypeShapeKind.VALUE -> requireNotNull(typeName)
    TypeShapeKind.SEQUENCE -> {
        val container = when (requireNotNull(sequenceKind)) {
            SequenceKind.ARRAY -> "kotlin.Array"
            SequenceKind.LIST -> "kotlin.collections.List"
            SequenceKind.COLLECTION -> "kotlin.collections.Collection"
        }
        "$container<${requireNotNull(elementShape).compatibilityTypeName()}>"
    }
    TypeShapeKind.MAP ->
        "kotlin.collections.Map<${requireNotNull(keyShape).compatibilityTypeName()}, " +
            "${requireNotNull(valueShape).compatibilityTypeName()}>"
}

internal fun TypeShapeDescriptor.compatibilityLeafTypeName(): String = when (kind) {
    TypeShapeKind.VALUE -> requireNotNull(typeName)
    TypeShapeKind.SEQUENCE -> requireNotNull(elementShape).compatibilityLeafTypeName()
    TypeShapeKind.MAP -> compatibilityTypeName()
}

internal fun TypeShapeDescriptor.compatibilityElementTypeName(): String? =
    elementShape?.compatibilityTypeName()

private fun sequenceKindFor(typeName: String): SequenceKind {
    val rawTypeName = typeName.substringBefore('<').removeSuffix("[]")
    return when {
        typeName.endsWith("[]") || rawTypeName == "kotlin.Array" -> SequenceKind.ARRAY
        rawTypeName.endsWith("List") -> SequenceKind.LIST
        else -> SequenceKind.COLLECTION
    }
}
