// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/** Immutable metadata describing a command artifact. */
public class CommandDescriptor @JvmOverloads constructor(
    /** Stable command name used by Arc metadata consumers. */
    public val name: String,
    /** Fully qualified artifact type name. */
    public val typeName: String,
    properties: List<PropertyDescriptor> = emptyList(),
    /** Compatibility view of explicit route metadata. */
    public val routeOptions: RouteOptions = RouteOptions(),
    /** Stable package segments locating the artifact. */
    location: List<String> = typeName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
    /** Authorization requirements emitted by code generation. */
    public val authorization: AuthorizationMetadata = AuthorizationMetadata(),
    /** Explicit path override, when declared. */
    public val explicitPath: String? = routeOptions.path,
    /** Whether warning validation results are blocking for this artifact. */
    public val treatWarningsAsErrors: Boolean = false,
    /** Fully qualified source name of the client-visible response type, when present. */
    responseTypeName: String? = null,
    /** Whether the client-visible response is a supported list, collection, or array. */
    responseIsEnumerable: Boolean = false
) {
    private val legacyResponseTypeName: String? = responseTypeName
    private val legacyResponseIsEnumerable: Boolean = responseIsEnumerable
    // Constructor-scoped mutation is required so the legacy primary constructor keeps its exact JVM descriptor.
    private var responseValuesBacking: List<CommandResponseValueDescriptor> = normalizeResponseValues(emptyList())

    /**
     * Creates command metadata with explicitly classified response values.
     *
     * The empty default lets Jackson map legacy format-3 metadata, where the property is absent.
     */
    @JsonCreator
    public constructor(
        name: String,
        typeName: String,
        properties: List<PropertyDescriptor> = emptyList(),
        routeOptions: RouteOptions = RouteOptions(),
        location: List<String> = typeName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        authorization: AuthorizationMetadata = AuthorizationMetadata(),
        explicitPath: String? = routeOptions.path,
        treatWarningsAsErrors: Boolean = false,
        responseTypeName: String? = null,
        responseIsEnumerable: Boolean = false,
        responseValues: List<CommandResponseValueDescriptor> = emptyList()
    ) : this(
        name,
        typeName,
        properties,
        routeOptions,
        location,
        authorization,
        explicitPath,
        treatWarningsAsErrors,
        responseTypeName,
        responseIsEnumerable
    ) {
        responseValuesBacking = normalizeResponseValues(responseValues)
    }

    public companion object {
        /** Creates command metadata with explicitly classified response values. */
        @JvmStatic
        public fun withResponseValues(
            name: String,
            typeName: String,
            responseValues: List<CommandResponseValueDescriptor>
        ): CommandDescriptor = CommandDescriptor(
            name = name,
            typeName = typeName,
            responseValues = responseValues
        )
    }

    /** Package segments locating the command, in declaration order. */
    public val location: List<String> = java.util.List.copyOf(location)

    /** Properties declared by the command, in declaration order. */
    public val properties: List<PropertyDescriptor> = java.util.List.copyOf(properties)

    /** Statically classified response values, in aggregate declaration order. */
    public val responseValues: List<CommandResponseValueDescriptor>
        get() = responseValuesBacking

    /** Compatibility projection of the client-visible response value's type name. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public val responseTypeName: String?
        get() = clientResponseValue()?.typeName ?: legacyResponseTypeName

    /** Compatibility projection of the client-visible response value's enumerable shape. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public val responseIsEnumerable: Boolean
        get() = clientResponseValue()?.isEnumerable ?: legacyResponseIsEnumerable

    private fun normalizeResponseValues(
        responseValues: List<CommandResponseValueDescriptor>
    ): List<CommandResponseValueDescriptor> {
        require(legacyResponseTypeName != null || !legacyResponseIsEnumerable) {
            "Legacy enumerable response metadata requires a response type name."
        }

        val normalized = if (responseValues.isEmpty() && legacyResponseTypeName != null) {
            listOf(
                CommandResponseValueDescriptor(
                    legacyResponseTypeName,
                    legacyResponseIsEnumerable,
                    CommandResponseValueDisposition.CLIENT
                )
            )
        } else {
            java.util.List.copyOf(responseValues)
        }
        val clientValues = normalized.filter { value ->
            value.disposition == CommandResponseValueDisposition.CLIENT
        }
        require(clientValues.size <= 1) {
            "Command response metadata must contain at most one client-visible response value."
        }

        if (responseValues.isNotEmpty() && legacyResponseTypeName != null) {
            val clientResponseValue = clientValues.singleOrNull()
            require(clientResponseValue != null) {
                "Legacy response metadata requires a matching client-visible response value."
            }
            require(
                clientResponseValue.typeName == legacyResponseTypeName &&
                    clientResponseValue.isEnumerable == legacyResponseIsEnumerable
            ) {
                "Legacy response metadata must match the client-visible response value."
            }
        }
        return normalized
    }

    private fun clientResponseValue(): CommandResponseValueDescriptor? =
        responseValuesBacking.singleOrNull { value -> value.disposition == CommandResponseValueDisposition.CLIENT }
}
