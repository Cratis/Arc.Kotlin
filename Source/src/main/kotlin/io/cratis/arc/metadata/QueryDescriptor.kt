// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType

/** Language-neutral metadata describing a query artifact. */
@JsonPropertyOrder(
    "name",
    "declaringTypeName",
    "returnShape",
    "parameters",
    "routeOptions",
    "fullyQualifiedName",
    "location",
    "authorization",
    "explicitPath",
    "queryHttpMethod",
    "transport",
    "supportsPaging",
    "supportsSorting",
    "treatWarningsAsErrors"
)
public class QueryDescriptor @JvmOverloads constructor(
    /** Stable query name used by Arc metadata consumers. */
    public val name: String,
    /** Fully qualified name of the type declaring the query. */
    public val declaringTypeName: String,
    /** Fully qualified or source-level query result type name. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val returnTypeName: String,
    parameters: List<ParameterDescriptor> = emptyList(),
    /** Compatibility view of explicit route metadata. */
    public val routeOptions: RouteOptions = RouteOptions(),
    /** Exact registry and proxy identity for the query. */
    public val fullyQualifiedName: String = "$declaringTypeName.$name",
    /** Stable package segments locating the declaring artifact. */
    location: List<String> = declaringTypeName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
    /** Authorization requirements emitted by code generation. */
    public val authorization: AuthorizationMetadata = AuthorizationMetadata(),
    /** Explicit @Path value, preserved verbatim by route calculation. */
    public val explicitPath: String? = routeOptions.path,
    /** Generated proxy HTTP method preference. */
    public val queryHttpMethod: QueryHttpMethodType = QueryHttpMethodType.AUTO,
    /** Observable versus request-response transport. */
    public val transport: QueryTransportType = routeOptions.transport,
    /** Whether the result type is enumerable. */
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public val isEnumerable: Boolean = false,
    /** Whether database-owned paging is supported. */
    public val supportsPaging: Boolean = false,
    /** Whether warning validation results are blocking for this query. */
    public val treatWarningsAsErrors: Boolean = false,
    /** Whether database-owned sorting is supported. */
    public val supportsSorting: Boolean = false
) {
    private var returnShapeBacking: TypeShapeDescriptor = legacyLeafShape(returnTypeName, false, isEnumerable)

    /** Creates query metadata from its canonical recursive return type shape. */
    @JvmOverloads
    public constructor(
        name: String,
        declaringTypeName: String,
        returnShape: TypeShapeDescriptor,
        parameters: List<ParameterDescriptor> = emptyList(),
        routeOptions: RouteOptions = RouteOptions(),
        fullyQualifiedName: String = "$declaringTypeName.$name",
        location: List<String> = declaringTypeName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        authorization: AuthorizationMetadata = AuthorizationMetadata(),
        explicitPath: String? = routeOptions.path,
        queryHttpMethod: QueryHttpMethodType = QueryHttpMethodType.AUTO,
        transport: QueryTransportType = routeOptions.transport,
        supportsPaging: Boolean = false,
        treatWarningsAsErrors: Boolean = false,
        supportsSorting: Boolean = false
    ) : this(
        name,
        declaringTypeName,
        returnShape.compatibilityLeafTypeName(),
        parameters,
        routeOptions,
        fullyQualifiedName,
        location,
        authorization,
        explicitPath,
        queryHttpMethod,
        transport,
        returnShape.kind == TypeShapeKind.SEQUENCE,
        supportsPaging,
        treatWarningsAsErrors,
        supportsSorting
    ) {
        returnShapeBacking = returnShape
    }

    /** Jackson compatibility creator accepting either legacy flat return metadata or the canonical return shape. */
    @JsonCreator
    public constructor(
        @JsonProperty("name") name: String,
        @JsonProperty("declaringTypeName") declaringTypeName: String,
        @JsonProperty("returnTypeName") returnTypeName: String?,
        @JsonProperty("parameters") parameters: List<ParameterDescriptor>?,
        @JsonProperty("routeOptions") routeOptions: RouteOptions?,
        @JsonProperty("fullyQualifiedName") fullyQualifiedName: String?,
        @JsonProperty("location") location: List<String>?,
        @JsonProperty("authorization") authorization: AuthorizationMetadata?,
        @JsonProperty("explicitPath") explicitPath: String?,
        @JsonProperty("queryHttpMethod") queryHttpMethod: QueryHttpMethodType?,
        @JsonProperty("transport") transport: QueryTransportType?,
        @JsonProperty("isEnumerable") isEnumerable: Boolean?,
        @JsonProperty("supportsPaging") supportsPaging: Boolean?,
        @JsonProperty("supportsSorting") supportsSorting: Boolean?,
        @JsonProperty("treatWarningsAsErrors") treatWarningsAsErrors: Boolean?,
        @JsonProperty("returnShape") returnShape: TypeShapeDescriptor?
    ) : this(
        name,
        declaringTypeName,
        resolveQueryReturnShape(returnTypeName, isEnumerable, returnShape),
        parameters.orEmpty(),
        routeOptions ?: RouteOptions(),
        fullyQualifiedName ?: "$declaringTypeName.$name",
        location ?: declaringTypeName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        authorization ?: AuthorizationMetadata(),
        explicitPath ?: routeOptions?.path,
        queryHttpMethod ?: QueryHttpMethodType.AUTO,
        transport ?: routeOptions?.transport ?: QueryTransportType.REQUEST_RESPONSE,
        supportsPaging ?: false,
        treatWarningsAsErrors ?: false,
        supportsSorting ?: false
    )

    /** Canonical recursive query return metadata. */
    @get:JsonProperty("returnShape")
    public val returnShape: TypeShapeDescriptor
        get() = returnShapeBacking

    /** Package segments locating the query declaration, in declaration order. */
    public val location: List<String> = java.util.List.copyOf(location)

    /** Parameters declared by the query, in declaration order. */
    public val parameters: List<ParameterDescriptor> = java.util.List.copyOf(parameters)
}

private fun resolveQueryReturnShape(
    returnTypeName: String?,
    enumerable: Boolean?,
    shape: TypeShapeDescriptor?
): TypeShapeDescriptor {
    if (shape == null) {
        require(!returnTypeName.isNullOrBlank()) {
            "Legacy query return metadata requires returnTypeName when returnShape is absent."
        }
        return legacyLeafShape(returnTypeName, false, enumerable ?: false)
    }

    require(enumerable == null || enumerable == (shape.kind == TypeShapeKind.SEQUENCE)) {
        "Legacy query enumerable metadata must match returnShape."
    }
    require(returnTypeName == null || returnTypeName == shape.compatibilityLeafTypeName()) {
        "Legacy query returnTypeName must match returnShape."
    }
    return shape
}
