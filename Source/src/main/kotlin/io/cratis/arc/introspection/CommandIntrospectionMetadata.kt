// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.introspection

import com.fasterxml.jackson.databind.JsonNode
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.PropertyDescriptor

/** Metadata for an introspected command endpoint. */
public class CommandIntrospectionMetadata(
    public val name: String,
    public val namespace: String,
    public val route: String,
    public val type: String,
    public val documentationSummary: String,
    public val payloadSchema: JsonNode,
    public val authorization: AuthorizationMetadata,
    properties: List<PropertyDescriptor>
) {
    public val properties: List<PropertyDescriptor> = java.util.List.copyOf(properties)
}
