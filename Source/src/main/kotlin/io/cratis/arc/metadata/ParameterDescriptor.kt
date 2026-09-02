// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/** Language-neutral query parameter metadata. */
@JsonPropertyOrder("name", "shape", "source", "hasDefault", "validationRules", "validateRecursively")
public class ParameterDescriptor @JvmOverloads constructor(
    /** Parameter name as declared by the operation. */
    public val name: String,
    /** Fully qualified or source-level parameter type name. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val typeName: String,
    /** Whether the parameter accepts null. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val isNullable: Boolean = false,
    /** Whether the parameter is supplied by the host's service container. */
    isFromServices: Boolean = false,
    /** Whether this parameter is a supported list, collection, or array. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val isEnumerable: Boolean = false,
    /** Fully qualified source name of the enumerable element, when enumerable. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val elementTypeName: String? = null,
    validationRules: List<ValidationRuleDescriptor> = emptyList(),
    /** Whether Jakarta @Valid requests recursive validation for this parameter. */
    public val validateRecursively: Boolean = false
) {
    private var shapeBacking: TypeShapeDescriptor =
        legacyParameterShape(typeName, isNullable, isEnumerable, elementTypeName)
    private var sourceBacking: QueryParameterSource =
        if (isFromServices) QueryParameterSource.SERVICE else QueryParameterSource.CLIENT
    private var hasDefaultBacking: Boolean = false

    init {
        require(isEnumerable == (elementTypeName != null)) {
            "Legacy parameter enumerable metadata requires exactly one elementTypeName."
        }
    }

    /** Creates parameter metadata from its canonical recursive type shape. */
    @JvmOverloads
    public constructor(
        name: String,
        shape: TypeShapeDescriptor,
        isFromServices: Boolean = false,
        validationRules: List<ValidationRuleDescriptor> = emptyList(),
        validateRecursively: Boolean = false
    ) : this(
        name,
        shape.compatibilityTypeName(),
        shape.nullable,
        isFromServices,
        shape.kind == TypeShapeKind.SEQUENCE,
        shape.compatibilityElementTypeName(),
        validationRules,
        validateRecursively
    ) {
        shapeBacking = shape
    }

    /** Creates parameter metadata from its canonical recursive type shape and value source. */
    @JvmOverloads
    public constructor(
        name: String,
        shape: TypeShapeDescriptor,
        source: QueryParameterSource,
        validationRules: List<ValidationRuleDescriptor> = emptyList(),
        validateRecursively: Boolean = false
    ) : this(
        name,
        shape,
        source == QueryParameterSource.SERVICE,
        validationRules,
        validateRecursively
    ) {
        sourceBacking = source
    }

    /** Creates parameter metadata including whether a client argument has a Kotlin default. */
    @JvmOverloads
    public constructor(
        name: String,
        shape: TypeShapeDescriptor,
        source: QueryParameterSource,
        hasDefault: Boolean,
        validationRules: List<ValidationRuleDescriptor> = emptyList(),
        validateRecursively: Boolean = false
    ) : this(name, shape, source, validationRules, validateRecursively) {
        hasDefaultBacking = requireValidParameterDefault(source, hasDefault)
    }

    /** Preserves the legacy Jackson constructor descriptor for programmatic callers. */
    public constructor(
        name: String,
        typeName: String?,
        isNullable: Boolean?,
        isFromServices: Boolean?,
        isEnumerable: Boolean?,
        elementTypeName: String?,
        validationRules: List<ValidationRuleDescriptor>?,
        validateRecursively: Boolean?,
        shape: TypeShapeDescriptor?
    ) : this(
        name,
        resolveParameterShape(typeName, isNullable, isEnumerable, elementTypeName, shape),
        resolveQueryParameterSource(isFromServices, null),
        validationRules.orEmpty(),
        validateRecursively ?: false
    )

    /** Preserves the canonical source constructor descriptor used before default metadata was added. */
    public constructor(
        name: String,
        typeName: String?,
        isNullable: Boolean?,
        isFromServices: Boolean?,
        isEnumerable: Boolean?,
        elementTypeName: String?,
        validationRules: List<ValidationRuleDescriptor>?,
        validateRecursively: Boolean?,
        shape: TypeShapeDescriptor?,
        source: QueryParameterSource?
    ) : this(
        name,
        resolveParameterShape(typeName, isNullable, isEnumerable, elementTypeName, shape),
        resolveQueryParameterSource(isFromServices, source),
        false,
        validationRules.orEmpty(),
        validateRecursively ?: false
    )

    /** Jackson compatibility creator accepting legacy projections and canonical metadata. */
    @JsonCreator
    public constructor(
        @JsonProperty("name") name: String,
        @JsonProperty("typeName") typeName: String?,
        @JsonProperty("isNullable") isNullable: Boolean?,
        @JsonProperty("isFromServices") isFromServices: Boolean?,
        @JsonProperty("isEnumerable") isEnumerable: Boolean?,
        @JsonProperty("elementTypeName") elementTypeName: String?,
        @JsonProperty("validationRules") validationRules: List<ValidationRuleDescriptor>?,
        @JsonProperty("validateRecursively") validateRecursively: Boolean?,
        @JsonProperty("shape") shape: TypeShapeDescriptor?,
        @JsonProperty("source") source: QueryParameterSource?,
        @JsonProperty("hasDefault") hasDefault: Boolean?
    ) : this(
        name,
        resolveParameterShape(typeName, isNullable, isEnumerable, elementTypeName, shape),
        resolveQueryParameterSource(isFromServices, source),
        hasDefault ?: false,
        validationRules.orEmpty(),
        validateRecursively ?: false
    )

    /** Canonical recursive type metadata. */
    @get:JsonProperty("shape")
    public val shape: TypeShapeDescriptor
        get() = shapeBacking

    /** Canonical source of this parameter's value. */
    public val source: QueryParameterSource
        get() = sourceBacking

    /** Whether the parameter is supplied by the host's service container. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public val isFromServices: Boolean
        get() = source == QueryParameterSource.SERVICE

    /** Whether an omitted client argument uses the Kotlin query parameter default. */
    public val hasDefault: Boolean
        get() = hasDefaultBacking

    /** Client-representable Jakarta validation rules in deterministic order. */
    public val validationRules: List<ValidationRuleDescriptor> = java.util.List.copyOf(validationRules)

    override fun equals(other: Any?): Boolean = other is ParameterDescriptor &&
        name == other.name && shape == other.shape && source == other.source && hasDefault == other.hasDefault &&
        validationRules == other.validationRules && validateRecursively == other.validateRecursively

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + shape.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + hasDefault.hashCode()
        result = 31 * result + validationRules.hashCode()
        return 31 * result + validateRecursively.hashCode()
    }

    override fun toString(): String =
        "ParameterDescriptor(name=$name, shape=$shape, source=$source, hasDefault=$hasDefault, " +
            "validationRules=$validationRules, validateRecursively=$validateRecursively)"
}

private fun requireValidParameterDefault(source: QueryParameterSource, hasDefault: Boolean): Boolean {
    require(!hasDefault || source == QueryParameterSource.CLIENT) {
        "Only client query parameters may declare a default value."
    }
    return hasDefault
}

private fun resolveQueryParameterSource(
    isFromServices: Boolean?,
    source: QueryParameterSource?
): QueryParameterSource {
    if (source == null) {
        return if (isFromServices == true) QueryParameterSource.SERVICE else QueryParameterSource.CLIENT
    }

    require(isFromServices == null || isFromServices == (source == QueryParameterSource.SERVICE)) {
        "Legacy parameter isFromServices must match source."
    }
    return source
}

private fun legacyParameterShape(
    typeName: String,
    nullable: Boolean,
    enumerable: Boolean,
    elementTypeName: String?
): TypeShapeDescriptor = legacyPropertyShape(typeName, nullable, enumerable, elementTypeName)

private fun resolveParameterShape(
    typeName: String?,
    nullable: Boolean?,
    enumerable: Boolean?,
    elementTypeName: String?,
    shape: TypeShapeDescriptor?
): TypeShapeDescriptor {
    if (shape == null) {
        require(!typeName.isNullOrBlank()) { "Legacy parameter metadata requires typeName when shape is absent." }
        return legacyParameterShape(typeName, nullable ?: false, enumerable ?: false, elementTypeName)
    }

    require(nullable == null || nullable == shape.nullable) {
        "Legacy parameter nullability must match shape."
    }
    require(enumerable == null || enumerable == (shape.kind == TypeShapeKind.SEQUENCE)) {
        "Legacy parameter enumerable metadata must match shape."
    }
    require(typeName == null || compatibleParameterContainerTypeName(typeName, shape)) {
        "Legacy parameter typeName must match shape."
    }
    require(elementTypeName == null || elementTypeName == shape.compatibilityElementTypeName()) {
        "Legacy parameter elementTypeName must match shape."
    }
    return shape
}

private fun compatibleParameterContainerTypeName(typeName: String, shape: TypeShapeDescriptor): Boolean {
    if (typeName == shape.compatibilityTypeName()) return true
    if (shape.kind != TypeShapeKind.SEQUENCE) return false
    return typeName.substringBefore('<') == shape.compatibilityTypeName().substringBefore('<')
}
