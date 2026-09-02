// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/** Language-neutral command or model property metadata. */
@JsonPropertyOrder("name", "shape", "isCommandKey", "validationRules", "validateRecursively", "derivatives")
public class PropertyDescriptor @JvmOverloads constructor(
    /** Property name as declared by the artifact. */
    public val name: String,
    /** Fully qualified or source-level property type name. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val typeName: String,
    /** Whether the property accepts null. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val isNullable: Boolean = false,
    /** Whether this property is the command key. */
    public val isCommandKey: Boolean = false,
    /** Whether this property is a supported list, collection, or array. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val isEnumerable: Boolean = false,
    /** Fully qualified source name of the enumerable element, when enumerable. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val elementTypeName: String? = null,
    validationRules: List<ValidationRuleDescriptor> = emptyList(),
    /** Whether Jakarta @Valid requests recursive validation for this property. */
    public val validateRecursively: Boolean = false,
    /** Concrete @DerivedType implementations when the property is declared as an interface. */
    derivatives: List<String> = emptyList()
) {
    private var shapeBacking: TypeShapeDescriptor =
        legacyPropertyShape(typeName, isNullable, isEnumerable, elementTypeName)

    init {
        require(isEnumerable == (elementTypeName != null)) {
            "Legacy property enumerable metadata requires exactly one elementTypeName."
        }
    }

    /** Creates property metadata from its canonical recursive type shape. */
    @JvmOverloads
    public constructor(
        name: String,
        shape: TypeShapeDescriptor,
        isCommandKey: Boolean = false,
        validationRules: List<ValidationRuleDescriptor> = emptyList(),
        validateRecursively: Boolean = false,
        derivatives: List<String> = emptyList()
    ) : this(
        name,
        shape.compatibilityTypeName(),
        shape.nullable,
        isCommandKey,
        shape.kind == TypeShapeKind.SEQUENCE,
        shape.compatibilityElementTypeName(),
        validationRules,
        validateRecursively,
        derivatives
    ) {
        shapeBacking = shape
    }

    /** Jackson compatibility creator accepting either legacy flat metadata or the canonical shape. */
    @JsonCreator
    public constructor(
        @JsonProperty("name") name: String,
        @JsonProperty("typeName") typeName: String?,
        @JsonProperty("isNullable") isNullable: Boolean?,
        @JsonProperty("isCommandKey") isCommandKey: Boolean?,
        @JsonProperty("isEnumerable") isEnumerable: Boolean?,
        @JsonProperty("elementTypeName") elementTypeName: String?,
        @JsonProperty("validationRules") validationRules: List<ValidationRuleDescriptor>?,
        @JsonProperty("validateRecursively") validateRecursively: Boolean?,
        @JsonProperty("derivatives") derivatives: List<String>?,
        @JsonProperty("shape") shape: TypeShapeDescriptor?
    ) : this(
        name,
        resolvePropertyShape(typeName, isNullable, isEnumerable, elementTypeName, shape),
        isCommandKey ?: false,
        validationRules.orEmpty(),
        validateRecursively ?: false,
        derivatives.orEmpty()
    )

    /** Canonical recursive type metadata. */
    @get:JsonProperty("shape")
    public val shape: TypeShapeDescriptor
        get() = shapeBacking

    /** Client-representable Jakarta validation rules in deterministic order. */
    public val validationRules: List<ValidationRuleDescriptor> = java.util.List.copyOf(validationRules)

    /** Fully qualified derivative names in deterministic order. */
    public val derivatives: List<String> = java.util.List.copyOf(derivatives)

    override fun equals(other: Any?): Boolean = other is PropertyDescriptor &&
        name == other.name && shape == other.shape && isCommandKey == other.isCommandKey &&
        validationRules == other.validationRules && validateRecursively == other.validateRecursively &&
        derivatives == other.derivatives

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + shape.hashCode()
        result = 31 * result + isCommandKey.hashCode()
        result = 31 * result + validationRules.hashCode()
        result = 31 * result + validateRecursively.hashCode()
        return 31 * result + derivatives.hashCode()
    }

    override fun toString(): String =
        "PropertyDescriptor(name=$name, shape=$shape, isCommandKey=$isCommandKey, " +
            "validationRules=$validationRules, validateRecursively=$validateRecursively, derivatives=$derivatives)"
}

private fun resolvePropertyShape(
    typeName: String?,
    nullable: Boolean?,
    enumerable: Boolean?,
    elementTypeName: String?,
    shape: TypeShapeDescriptor?
): TypeShapeDescriptor {
    if (shape == null) {
        require(!typeName.isNullOrBlank()) { "Legacy property metadata requires typeName when shape is absent." }
        return legacyPropertyShape(typeName, nullable ?: false, enumerable ?: false, elementTypeName)
    }

    require(nullable == null || nullable == shape.nullable) {
        "Legacy property nullability must match shape."
    }
    require(enumerable == null || enumerable == (shape.kind == TypeShapeKind.SEQUENCE)) {
        "Legacy property enumerable metadata must match shape."
    }
    require(typeName == null || compatibleContainerTypeName(typeName, shape)) {
        "Legacy property typeName must match shape."
    }
    require(elementTypeName == null || elementTypeName == shape.compatibilityElementTypeName()) {
        "Legacy property elementTypeName must match shape."
    }
    return shape
}

private fun compatibleContainerTypeName(typeName: String, shape: TypeShapeDescriptor): Boolean {
    if (typeName == shape.compatibilityTypeName()) return true
    if (shape.kind != TypeShapeKind.SEQUENCE) return false
    return typeName.substringBefore('<') == shape.compatibilityTypeName().substringBefore('<')
}
