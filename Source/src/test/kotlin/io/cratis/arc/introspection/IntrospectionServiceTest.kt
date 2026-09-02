// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.introspection

import com.fasterxml.jackson.databind.JsonNode
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class IntrospectionServiceTest {
    @Test
    fun `metadata is deterministic cached and refreshed when registries change`() {
        val commands = ConcurrentCommandHandlerRegistry()
        val queries = ConcurrentQueryPerformerRegistry()
        commands.register(command(ZCommand::class.java, "ZCommand"))
        commands.register(command(ACommand::class.java, "ACommand"))
        queries.register(query("io.example.ZModel.zed"))
        queries.register(query("io.example.AModel.all", "/people/live", QueryTransportType.OBSERVABLE))
        val service = DefaultIntrospectionService(commands, queries, ApiEndpointOptions(routePrefix = "api"))

        val firstCommands = service.commands
        val firstQueries = service.queries

        assertSame(firstCommands, service.commands)
        assertEquals(listOf("ACommand", "ZCommand"), firstCommands.map { it.name })
        assertEquals(listOf("all", "zed"), firstQueries.map { it.name })
        assertEquals("/people/live", firstQueries.first().route)
        assertEquals(QueryTransportType.OBSERVABLE, firstQueries.first().transport)
        assertTrue(firstQueries.first().supportsPaging)
        assertTrue(firstQueries.first().supportsSorting)
        assertEquals(listOf("name"), firstQueries.first().parameters.map { it.name })
        assertEquals("string", firstCommands.first().payloadSchema["properties"]["value"]["type"].textValue())

        queries.register(query("io.example.BModel.byId"))
        assertEquals(3, service.queries.size)
    }

    @Test
    fun `query introspection exposes default capability without requiring or inventing a value`() {
        val queries = ConcurrentQueryPerformerRegistry()
        queries.register(
            query(
                "io.example.Model.defaulted",
                parameters = listOf(
                    ParameterDescriptor("required", "kotlin.String"),
                    ParameterDescriptor(
                        "limit",
                        TypeShapeDescriptor.value("kotlin.Int"),
                        QueryParameterSource.CLIENT,
                        hasDefault = true
                    )
                )
            )
        )
        val metadata = DefaultIntrospectionService(ConcurrentCommandHandlerRegistry(), queries).queries.single()

        assertEquals(listOf("required"), metadata.argumentsSchema["required"].map(JsonNode::textValue))
        assertTrue(metadata.parameters.single { parameter -> parameter.name == "limit" }.hasDefault)
        val json = ArcObjectMapper.create().valueToTree<JsonNode>(metadata)
        val defaulted = json["parameters"].single { parameter -> parameter["name"].textValue() == "limit" }
        assertTrue(defaulted["hasDefault"].booleanValue())
        assertFalse(defaulted.has("default"))
        assertFalse(defaulted.has("defaultExpression"))
        assertFalse(defaulted.has("defaultValue"))
    }

    @Test
    fun `command schemas classify direct and enumerable terminal textual scalars as strings`() {
        val commands = ConcurrentCommandHandlerRegistry()
        commands.register(command(ACommand::class.java, "ACommand", terminalTextualProperties()))
        val service = DefaultIntrospectionService(commands, ConcurrentQueryPerformerRegistry())

        assertTerminalTextualSchemas(service.commands.single().payloadSchema["properties"])
    }

    @Test
    fun `query schemas classify direct and enumerable terminal textual scalars as strings`() {
        val queries = ConcurrentQueryPerformerRegistry()
        queries.register(query("io.example.Model.scalars", parameters = terminalTextualParameters()))
        val service = DefaultIntrospectionService(ConcurrentCommandHandlerRegistry(), queries)

        assertTerminalTextualSchemas(service.queries.single().argumentsSchema["properties"])
    }

    @Test
    fun `non scalar object models remain object schemas`() {
        val commands = ConcurrentCommandHandlerRegistry()
        val queries = ConcurrentQueryPerformerRegistry()
        commands.register(
            command(
                ACommand::class.java,
                "ACommand",
                listOf(
                    PropertyDescriptor("model", "io.example.Model"),
                    PropertyDescriptor(
                        "models",
                        "kotlin.collections.List",
                        isEnumerable = true,
                        elementTypeName = "io.example.Model"
                    )
                )
            )
        )
        queries.register(
            query(
                "io.example.Model.objects",
                parameters = listOf(
                    ParameterDescriptor("model", "io.example.Model"),
                    ParameterDescriptor(
                        "models",
                        "kotlin.collections.List",
                        isEnumerable = true,
                        elementTypeName = "io.example.Model"
                    )
                )
            )
        )
        val service = DefaultIntrospectionService(commands, queries)

        listOf(
            service.commands.single().payloadSchema["properties"],
            service.queries.single().argumentsSchema["properties"]
        ).forEach { properties ->
            assertEquals("object", properties["model"]["type"].textValue())
            assertEquals("io.example.Model", properties["model"]["javaType"].textValue())
            assertEquals("array", properties["models"]["type"].textValue())
            assertEquals("object", properties["models"]["items"]["type"].textValue())
            assertEquals("io.example.Model", properties["models"]["items"]["javaType"].textValue())
        }
    }

    private fun command(
        type: Class<*>,
        name: String,
        properties: List<PropertyDescriptor> = listOf(PropertyDescriptor("value", "kotlin.String"))
    ): CommandHandler = object : CommandHandler {
        override val commandType: Class<*> = type
        override val metadata = CommandDescriptor(
            name,
            type.name,
            properties,
            authorization = AuthorizationMetadata(roles = listOf("admin"))
        )
        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private fun query(
        fullyQualifiedName: String,
        path: String? = null,
        transport: QueryTransportType = QueryTransportType.REQUEST_RESPONSE,
        parameters: List<ParameterDescriptor> = listOf(ParameterDescriptor("name", "kotlin.String"))
    ): QueryPerformer = object : QueryPerformer {
        private val declaringType = fullyQualifiedName.substringBeforeLast('.')
        private val queryName = fullyQualifiedName.substringAfterLast('.')
        override val descriptor = QueryDescriptor(
            queryName,
            declaringType,
            "kotlin.String",
            parameters + listOf(
                ParameterDescriptor("service", "java.lang.Object", isFromServices = true),
                ParameterDescriptor(
                    "request",
                    TypeShapeDescriptor.value(QueryRequest::class.java.name),
                    QueryParameterSource.QUERY_REQUEST
                ),
                ParameterDescriptor(
                    "context",
                    TypeShapeDescriptor.value(QueryContext::class.java.name),
                    QueryParameterSource.QUERY_CONTEXT
                ),
                ParameterDescriptor(
                    "pageable",
                    TypeShapeDescriptor.value("org.springframework.data.domain.Pageable"),
                    QueryParameterSource.HOST_ADAPTER
                )
            ),
            RouteOptions(path = path, transport = transport),
            fullyQualifiedName = fullyQualifiedName,
            explicitPath = path,
            transport = transport,
            supportsPaging = transport == QueryTransportType.OBSERVABLE,
            supportsSorting = transport == QueryTransportType.OBSERVABLE
        )
        override val fullyQualifiedName = FullyQualifiedQueryName(fullyQualifiedName)
        override suspend fun perform(context: QueryContext): Any? =
            if (transport == QueryTransportType.OBSERVABLE) emptyFlow<Any>() else null
    }

    private fun terminalTextualProperties(): List<PropertyDescriptor> = listOf(
        PropertyDescriptor("localTime", "java.time.LocalTime"),
        PropertyDescriptor("duration", "java.time.Duration"),
        PropertyDescriptor("uuid", "java.util.UUID"),
        PropertyDescriptor(
            "localTimes",
            "kotlin.collections.List",
            isEnumerable = true,
            elementTypeName = "java.time.LocalTime"
        ),
        PropertyDescriptor(
            "durations",
            "kotlin.collections.List",
            isEnumerable = true,
            elementTypeName = "java.time.Duration"
        ),
        PropertyDescriptor(
            "uuids",
            "kotlin.collections.List",
            isEnumerable = true,
            elementTypeName = "kotlin.uuid.Uuid"
        )
    )

    private fun terminalTextualParameters(): List<ParameterDescriptor> = listOf(
        ParameterDescriptor("localTime", "java.time.LocalTime"),
        ParameterDescriptor("duration", "java.time.Duration"),
        ParameterDescriptor("uuid", "java.util.UUID"),
        ParameterDescriptor(
            "localTimes",
            "kotlin.collections.List",
            isEnumerable = true,
            elementTypeName = "java.time.LocalTime"
        ),
        ParameterDescriptor(
            "durations",
            "kotlin.collections.List",
            isEnumerable = true,
            elementTypeName = "java.time.Duration"
        ),
        ParameterDescriptor(
            "uuids",
            "kotlin.collections.List",
            isEnumerable = true,
            elementTypeName = "kotlin.uuid.Uuid"
        )
    )

    private fun assertTerminalTextualSchemas(properties: JsonNode) {
        assertEquals("string", properties["localTime"]["type"].textValue())
        assertEquals("string", properties["duration"]["type"].textValue())
        assertEquals("string", properties["uuid"]["type"].textValue())
        assertEquals("array", properties["localTimes"]["type"].textValue())
        assertEquals("string", properties["localTimes"]["items"]["type"].textValue())
        assertEquals("array", properties["durations"]["type"].textValue())
        assertEquals("string", properties["durations"]["items"]["type"].textValue())
        assertEquals("array", properties["uuids"]["type"].textValue())
        assertEquals("string", properties["uuids"]["items"]["type"].textValue())
    }

    private class ACommand
    private class ZCommand
}
