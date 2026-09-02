// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.introspection

import com.fasterxml.jackson.databind.JsonNode
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType

/** Metadata for an introspected query endpoint. */
public class QueryIntrospectionMetadata(
    public val name: String,
    public val namespace: String,
    public val route: String,
    public val fullyQualifiedName: String,
    public val type: String,
    public val documentationSummary: String,
    public val argumentsSchema: JsonNode,
    public val authorization: AuthorizationMetadata,
    parameters: List<ParameterDescriptor>,
    public val transport: QueryTransportType,
    public val supportsPaging: Boolean,
    public val supportsSorting: Boolean,
    public val queryHttpMethod: QueryHttpMethodType
) {
    /** Creates legacy query introspection metadata without explicit sorting capability. */
    public constructor(
        name: String,
        namespace: String,
        route: String,
        fullyQualifiedName: String,
        type: String,
        documentationSummary: String,
        argumentsSchema: JsonNode,
        authorization: AuthorizationMetadata,
        parameters: List<ParameterDescriptor>,
        transport: QueryTransportType,
        supportsPaging: Boolean,
        queryHttpMethod: QueryHttpMethodType
    ) : this(
        name,
        namespace,
        route,
        fullyQualifiedName,
        type,
        documentationSummary,
        argumentsSchema,
        authorization,
        parameters,
        transport,
        supportsPaging,
        false,
        queryHttpMethod
    )

    public val parameters: List<ParameterDescriptor> = java.util.List.copyOf(parameters)
}
