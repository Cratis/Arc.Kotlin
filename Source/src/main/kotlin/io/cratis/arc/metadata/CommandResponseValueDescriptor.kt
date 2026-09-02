// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/** Describes how one statically classified command response value is exposed or consumed. */
@JsonPropertyOrder("shape", "disposition")
public class CommandResponseValueDescriptor(
    /** Fully qualified source name of the response value's leaf type. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val typeName: String,
    /** Whether the leaf is a supported list, collection, or array. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val isEnumerable: Boolean,
    /** Whether the leaf is returned to the client or consumed by a response value handler. */
    public val disposition: CommandResponseValueDisposition
) {
    private var shapeBacking: TypeShapeDescriptor = legacyLeafShape(typeName, false, isEnumerable)

    /** Creates response metadata from its canonical recursive type shape. */
    public constructor(
        shape: TypeShapeDescriptor,
        disposition: CommandResponseValueDisposition
    ) : this(
        shape.compatibilityLeafTypeName(),
        shape.kind == TypeShapeKind.SEQUENCE,
        disposition
    ) {
        shapeBacking = shape
    }

    /** Jackson compatibility creator accepting either legacy flat metadata or the canonical shape. */
    @JsonCreator
    public constructor(
        @JsonProperty("typeName") typeName: String?,
        @JsonProperty("isEnumerable") isEnumerable: Boolean?,
        @JsonProperty("disposition") disposition: CommandResponseValueDisposition,
        @JsonProperty("shape") shape: TypeShapeDescriptor?
    ) : this(resolveCommandResponseShape(typeName, isEnumerable, shape), disposition)

    /** Canonical recursive type metadata. */
    @get:JsonProperty("shape")
    public val shape: TypeShapeDescriptor
        get() = shapeBacking

    /** Compatibility component exposing [typeName]. */
    public operator fun component1(): String = typeName

    /** Compatibility component exposing [isEnumerable]. */
    public operator fun component2(): Boolean = isEnumerable

    /** Compatibility component exposing [disposition]. */
    public operator fun component3(): CommandResponseValueDisposition = disposition

    /** Compatibility copy operation preserving the canonical shape when flat projections are unchanged. */
    public fun copy(
        typeName: String = this.typeName,
        isEnumerable: Boolean = this.isEnumerable,
        disposition: CommandResponseValueDisposition = this.disposition
    ): CommandResponseValueDescriptor = if (typeName == this.typeName && isEnumerable == this.isEnumerable) {
        CommandResponseValueDescriptor(shape, disposition)
    } else {
        CommandResponseValueDescriptor(typeName, isEnumerable, disposition)
    }

    override fun equals(other: Any?): Boolean = other is CommandResponseValueDescriptor &&
        shape == other.shape && disposition == other.disposition

    override fun hashCode(): Int = 31 * shape.hashCode() + disposition.hashCode()

    override fun toString(): String =
        "CommandResponseValueDescriptor(shape=$shape, disposition=$disposition)"
}

private fun resolveCommandResponseShape(
    typeName: String?,
    enumerable: Boolean?,
    shape: TypeShapeDescriptor?
): TypeShapeDescriptor {
    if (shape == null) {
        require(!typeName.isNullOrBlank()) { "Legacy command response metadata requires typeName when shape is absent." }
        return legacyLeafShape(typeName, false, enumerable ?: false)
    }

    require(enumerable == null || enumerable == (shape.kind == TypeShapeKind.SEQUENCE)) {
        "Legacy command response enumerable metadata must match shape."
    }
    require(typeName == null || typeName == shape.compatibilityLeafTypeName()) {
        "Legacy command response typeName must match shape."
    }
    return shape
}

/** Identifies the consumer of a statically classified command response value. */
public enum class CommandResponseValueDisposition {
    /** Serialized as the command's client-visible response. */
    CLIENT,

    /** Consumed by a registered [io.cratis.arc.commands.CommandResponseValueHandler]. */
    HANDLED
}
