// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ArcOpenApiDirectScalarTests {
    @Test
    fun `direct temporal and UUID command properties use exact scalar formats`() {
        val openApi = generateOpenApi()
        val commandSchema = requireNotNull(openApi.components.schemas["UseDirectScalars"])

        assertDirectScalarProperties(commandSchema)
        assertDirectScalarArrays(commandSchema)

        val command = openApi.paths.values.mapNotNull { path -> path.post }
            .single { operation -> operation.operationId == "Execute$DIRECT_SCALAR_COMMAND" }
        assertEquals(
            "#/components/schemas/UseDirectScalars",
            command.requestBody.content["application/json"]?.schema?.`$ref`
        )
    }

    @Test
    fun `direct temporal and UUID one-shot query parameters preserve scalar and array formats`() {
        val query = requireNotNull(generateOpenApi().paths[DIRECT_SCALAR_QUERY_PATH]?.get)
        val parameters = query.parameters.associateBy { parameter -> parameter.name }

        directScalarFormats.forEach { (name, format) ->
            assertStringSchema(parameters[name]?.schema, format, "query parameter $name")
        }
        directArrayFormats.forEach { (name, format) ->
            assertArraySchema(parameters[name]?.schema, format, "query parameter $name")
        }
    }

    @Test
    fun `direct temporal and UUID model properties and result model references use exact schemas`() {
        val openApi = generateOpenApi()
        val modelSchema = requireNotNull(openApi.components.schemas["DirectScalarReadModel"])

        assertDirectScalarProperties(modelSchema)
        assertDirectScalarArrays(modelSchema)

        val query = requireNotNull(openApi.paths[DIRECT_SCALAR_QUERY_PATH]?.get)
        val queryResult = requireNotNull(query.responses["200"]?.content?.get("application/json")?.schema)
        assertNullableReference(queryResult.properties["data"], "#/components/schemas/DirectScalarReadModel", "query data")

        val changeSet = requireNotNull(queryResult.properties["changeSet"]?.anyOf?.first())
        listOf("added", "replaced", "removed").forEach { propertyName ->
            val property = requireNotNull(changeSet.properties[propertyName])
            assertEquals("array", property.type, "query changes $propertyName")
            assertEquals(
                "#/components/schemas/DirectScalarReadModel",
                property.items?.`$ref`,
                "query changes $propertyName items"
            )
        }

        val command = openApi.paths.values.mapNotNull { path -> path.post }
            .single { operation -> operation.operationId == "Execute$DIRECT_SCALAR_COMMAND" }
        val commandResult = requireNotNull(command.responses["200"]?.content?.get("application/json")?.schema)
        assertNullableReference(
            commandResult.properties["response"],
            "#/components/schemas/DirectScalarReadModel",
            "command response"
        )
    }

    private fun generateOpenApi(): OpenAPI = ArcOpenApiGenerator().generate(listOf(DirectScalarFixtureModule())).openApi

    private fun assertDirectScalarProperties(schema: Schema<*>) {
        directScalarFormats.forEach { (name, format) ->
            assertStringSchema(schema.properties[name], format, "model property $name")
        }
    }

    private fun assertDirectScalarArrays(schema: Schema<*>) {
        directArrayFormats.forEach { (name, format) ->
            assertArraySchema(schema.properties[name], format, "model property $name")
        }
    }

    private fun assertStringSchema(schema: Schema<*>?, format: String, subject: String) {
        val scalar = requireNotNull(schema)
        assertEquals("string", scalar.type, subject)
        assertEquals(format, scalar.format, subject)
        assertNull(scalar.`$ref`, "$subject must be an inline scalar")
        assertTrue(scalar.properties.isNullOrEmpty(), "$subject must not be an object schema")
    }

    private fun assertArraySchema(schema: Schema<*>?, itemFormat: String, subject: String) {
        val array = requireNotNull(schema)
        assertEquals("array", array.type, subject)
        assertStringSchema(array.items, itemFormat, "$subject items")
    }

    private fun assertNullableReference(schema: Schema<*>?, reference: String, subject: String) {
        val members = requireNotNull(schema?.anyOf)
        assertEquals(2, members.size, subject)
        assertEquals(reference, members.first().`$ref`, subject)
        assertEquals("null", members.last().type, subject)
    }

    private class DirectScalarFixtureModule : ArcArtifactModule(
        commandHandlers = listOf(DirectScalarCommandHandler),
        queryPerformers = listOf(DirectScalarQueryPerformer),
        types = listOf(
            TypeDescriptor(
                "DirectScalarReadModel",
                DIRECT_SCALAR_MODEL,
                listOf("sample", "direct"),
                directProperties()
            )
        )
    )

    private object DirectScalarCommandHandler : CommandHandler {
        override val commandType: Class<*> = DirectScalarCommand::class.java
        override val metadata = CommandDescriptor(
            "UseDirectScalars",
            DIRECT_SCALAR_COMMAND,
            directProperties(),
            responseTypeName = DIRECT_SCALAR_MODEL
        )

        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private object DirectScalarQueryPerformer : QueryPerformer {
        override val descriptor = QueryDescriptor(
            "byDirectScalars",
            DIRECT_SCALAR_QUERY,
            DIRECT_SCALAR_MODEL,
            directParameters(),
            RouteOptions(DIRECT_SCALAR_QUERY_PATH),
            explicitPath = DIRECT_SCALAR_QUERY_PATH,
            queryHttpMethod = QueryHttpMethodType.QUERY,
            transport = QueryTransportType.REQUEST_RESPONSE
        )
        override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
        override suspend fun perform(context: QueryContext): Any? = null
    }

    private class DirectScalarCommand

    private companion object {
        const val DIRECT_SCALAR_COMMAND = "sample.direct.UseDirectScalars"
        const val DIRECT_SCALAR_MODEL = "sample.direct.DirectScalarReadModel"
        const val DIRECT_SCALAR_QUERY = "sample.direct.ByDirectScalars"
        const val DIRECT_SCALAR_QUERY_PATH = "/direct-scalars/query"

        val directScalarTypes = linkedMapOf(
            "localDate" to "java.time.LocalDate",
            "localTime" to "java.time.LocalTime",
            "kotlinUuid" to "kotlin.uuid.Uuid",
            "javaUuid" to "java.util.UUID",
            "instant" to "java.time.Instant",
            "duration" to "java.time.Duration",
            "shortDuration" to "Duration"
        )
        val directScalarFormats = linkedMapOf(
            "localDate" to "date",
            "localTime" to "time",
            "kotlinUuid" to "uuid",
            "javaUuid" to "uuid",
            "instant" to "date-time",
            "duration" to "duration",
            "shortDuration" to "duration"
        )
        val directArrayFormats = directScalarFormats.mapKeys { (name, _) -> "${name}s" }

        fun directProperties(): List<PropertyDescriptor> = directScalarTypes.map { (name, typeName) ->
            PropertyDescriptor(name, typeName)
        } + directScalarTypes.map { (name, typeName) ->
            PropertyDescriptor("${name}s", "kotlin.collections.List<$typeName>", isEnumerable = true, elementTypeName = typeName)
        }

        fun directParameters(): List<ParameterDescriptor> = directScalarTypes.map { (name, typeName) ->
            ParameterDescriptor(name, typeName)
        } + directScalarTypes.map { (name, typeName) ->
            ParameterDescriptor("${name}s", "kotlin.collections.List<$typeName>", isEnumerable = true, elementTypeName = typeName)
        }
    }
}
