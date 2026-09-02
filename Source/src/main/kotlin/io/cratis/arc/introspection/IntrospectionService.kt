// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.introspection

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.queries.QueryPerformerRegistry

private val terminalTextualScalarTypeNames = setOf(
    "kotlin.String",
    "java.lang.String",
    "String",
    "java.util.UUID",
    "kotlin.uuid.Uuid",
    "java.time.LocalDate",
    "java.time.LocalTime",
    "java.time.LocalDateTime",
    "java.time.Instant",
    "java.time.OffsetDateTime",
    "java.time.ZonedDateTime",
    "java.time.OffsetTime",
    "java.time.Duration",
    "java.time.Period"
)

private val booleanScalarTypeNames = setOf(
    "kotlin.Boolean",
    "java.lang.Boolean",
    "boolean",
    "Boolean"
)

private val integerScalarTypeNames = setOf(
    "kotlin.Byte",
    "kotlin.Short",
    "kotlin.Int",
    "kotlin.Long",
    "java.lang.Byte",
    "java.lang.Short",
    "java.lang.Integer",
    "java.lang.Long",
    "byte",
    "short",
    "int",
    "long"
)

private val numberScalarTypeNames = setOf(
    "kotlin.Float",
    "kotlin.Double",
    "java.lang.Float",
    "java.lang.Double",
    "float",
    "double",
    "java.math.BigDecimal"
)

private fun scalarSchemaType(typeName: String): String? = when (typeName.removeSuffix("?")) {
    in terminalTextualScalarTypeNames -> "string"
    in booleanScalarTypeNames -> "boolean"
    in integerScalarTypeNames -> "integer"
    in numberScalarTypeNames -> "number"
    else -> null
}

/** Provides deterministic metadata for registered Arc HTTP endpoints. */
public interface IntrospectionService {
    /** Registered commands ordered by command type name. */
    public val commands: List<CommandIntrospectionMetadata>

    /** Registered queries ordered by fully qualified query name. */
    public val queries: List<QueryIntrospectionMetadata>
}

/** Registry-backed introspection service whose immutable cache refreshes only when a registry changes. */
public class DefaultIntrospectionService(
    private val commandHandlers: CommandHandlerRegistry,
    private val queryPerformers: QueryPerformerRegistry,
    private val endpointOptions: ApiEndpointOptions = ApiEndpointOptions()
) : IntrospectionService {
    @Volatile
    private var cache: Cache? = null

    override val commands: List<CommandIntrospectionMetadata>
        get() = current().commands

    override val queries: List<QueryIntrospectionMetadata>
        get() = current().queries

    private fun current(): Cache {
        val commandVersion = commandHandlers.version
        val queryVersion = queryPerformers.version
        cache?.takeIf { it.commandVersion == commandVersion && it.queryVersion == queryVersion }?.let { return it }
        return synchronized(this) {
            cache?.takeIf { it.commandVersion == commandVersion && it.queryVersion == queryVersion }
                ?: build(commandVersion, queryVersion).also { cache = it }
        }
    }

    private fun build(commandVersion: Long, queryVersion: Long): Cache {
        val handlers = commandHandlers.snapshot()
        val commandCounts = handlers.groupingBy { handler ->
            handler.metadata.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        val commands = handlers.map { handler ->
            val descriptor = handler.metadata
            val namespace = descriptor.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
            CommandIntrospectionMetadata(
                descriptor.name,
                namespace,
                EndpointRouteHelper.commandRoute(descriptor, endpointOptions, (commandCounts[namespace] ?: 0) > 1),
                descriptor.typeName,
                "",
                objectSchema(descriptor.properties),
                descriptor.authorization,
                descriptor.properties
            )
        }

        val performers = queryPerformers.snapshot()
        val queryCounts = performers.groupingBy { performer ->
            performer.descriptor.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        val queries = performers.map { performer ->
            val descriptor = performer.descriptor
            val namespace = descriptor.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
            val parameters = descriptor.parameters.filter { parameter ->
                parameter.source == QueryParameterSource.CLIENT
            }
            QueryIntrospectionMetadata(
                descriptor.name,
                namespace,
                EndpointRouteHelper.queryRoute(descriptor, endpointOptions, (queryCounts[namespace] ?: 0) > 1),
                descriptor.fullyQualifiedName,
                descriptor.declaringTypeName,
                "",
                argumentsSchema(parameters),
                descriptor.authorization,
                parameters,
                descriptor.transport,
                descriptor.supportsPaging,
                descriptor.supportsSorting,
                descriptor.queryHttpMethod
            )
        }
        return Cache(commandVersion, queryVersion, java.util.List.copyOf(commands), java.util.List.copyOf(queries))
    }

    private fun objectSchema(properties: List<PropertyDescriptor>): JsonNode {
        val schema = JsonNodeFactory.instance.objectNode().put("type", "object")
        val propertySchemas = schema.putObject("properties")
        val required = schema.putArray("required")
        properties.forEach { property ->
            propertySchemas.set<ObjectNode>(property.name, typeSchema(property.typeName, property.isEnumerable, property.elementTypeName))
            if (!property.isNullable) required.add(property.name)
        }
        if (required.isEmpty) schema.remove("required")
        return schema
    }

    private fun argumentsSchema(parameters: List<ParameterDescriptor>): JsonNode {
        val schema = JsonNodeFactory.instance.objectNode().put("type", "object")
        val propertySchemas = schema.putObject("properties")
        val required = schema.putArray("required")
        parameters.forEach { parameter ->
            propertySchemas.set<ObjectNode>(parameter.name, typeSchema(parameter.typeName, parameter.isEnumerable, parameter.elementTypeName))
            if (!parameter.isNullable && !parameter.hasDefault) required.add(parameter.name)
        }
        if (required.isEmpty) schema.remove("required")
        return schema
    }

    private fun typeSchema(typeName: String, enumerable: Boolean, elementTypeName: String?): ObjectNode {
        if (enumerable) {
            return JsonNodeFactory.instance.objectNode().put("type", "array").set(
                "items",
                typeSchema(elementTypeName ?: "java.lang.Object", false, null)
            )
        }
        val schema = JsonNodeFactory.instance.objectNode()
        val scalarType = scalarSchemaType(typeName)
        return if (scalarType != null) {
            schema.put("type", scalarType)
        } else {
            schema.put("type", "object").put("javaType", typeName)
        }
    }

    private data class Cache(
        val commandVersion: Long,
        val queryVersion: Long,
        val commands: List<CommandIntrospectionMetadata>,
        val queries: List<QueryIntrospectionMetadata>
    )
}
