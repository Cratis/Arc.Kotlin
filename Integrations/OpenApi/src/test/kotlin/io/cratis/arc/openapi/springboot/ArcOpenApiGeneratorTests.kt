// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.EnumMemberDescriptor
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import io.swagger.v3.oas.models.media.Schema
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ArcOpenApiGeneratorTests {
    @Test
    fun `document uses exact routes schemas enums responses security and custom query paths`() {
        val document = ArcOpenApiGenerator().generate(
            listOf(FixtureModule()),
            ApiEndpointOptions(routePrefix = "service", segmentsToSkipForRoute = 3),
            identityProviderPresent = true
        )
        val openApi = document.openApi

        assertEquals("3.1.0", openApi.openapi)
        val commandPath = openApi.paths["/service/widgets/create-widget"]
        assertNotNull(commandPath?.post)
        assertNotNull(openApi.paths["/service/widgets/create-widget/validate"]?.post)
        assertNotNull(openApi.paths["/custom/widgets"]?.get)
        assertFalse(document.json().decodeToString().contains("\"query\" :"))
        assertNotNull(openApi.paths["/.cratis/me"]?.get)
        assertNotNull(openApi.paths["/.cratis/identity-details/schema"]?.get)

        val command = requireNotNull(commandPath?.post)
        assertEquals("Executeio.cratis.samples.widgets.CreateWidget", command.operationId)
        assertEquals(listOf("widgets"), command.tags)
        assertEquals(listOf("admin", "operator"), command.extensions["x-roles"])
        assertEquals(listOf("Bearer"), command.security.single().keys.toList())
        assertEquals(setOf("200", "400", "403", "500"), command.responses.keys)
        assertEquals(
            "#/components/schemas/CreateWidget",
            command.requestBody.content["application/json"]?.schema?.`$ref`
        )

        val query = requireNotNull(openApi.paths["/custom/widgets"]?.get)
        assertEquals(setOf("200", "202", "400", "403", "500"), query.responses.keys)
        assertEquals(
            listOf("name", "limit", "states", "page", "pageSize", "sortBy", "sortDirection"),
            query.parameters.map { it.name }
        )
        assertFalse(query.parameters.first { it.name == "limit" }.required)
        assertNull(query.parameters.first { it.name == "limit" }.schema.default)
        assertFalse(query.parameters.first { it.name == "states" }.required)

        val commandSchema = requireNotNull(openApi.components.schemas["CreateWidget"])
        assertEquals(listOf("labels", "name", "state"), commandSchema.required)
        assertNotNull(commandSchema.properties["note"]?.anyOf)
        assertEquals("array", commandSchema.properties["labels"]?.type)

        val enumSchema = requireNotNull(openApi.components.schemas["WidgetState"])
        assertEquals("integer", enumSchema.type)
        assertEquals(listOf(10, 20), enumSchema.enum)
        assertEquals(listOf("OPEN", "CLOSED"), enumSchema.extensions["x-enumNames"])

        assertEquals("http", openApi.components.securitySchemes["Bearer"]?.type?.toString()?.lowercase())
        assertEquals("bearer", openApi.components.securitySchemes["Bearer"]?.scheme)
        assertTrue(document.json().decodeToString().contains("\"openapi\" : \"3.1.0\""))
        assertArrayEquals(document.json(), ArcOpenApiGenerator().generate(
            listOf(FixtureModule()),
            ApiEndpointOptions(routePrefix = "service", segmentsToSkipForRoute = 3),
            identityProviderPresent = true
        ).json())
    }

    @Test
    fun `Kotlin and Java concepts use reusable underlying scalar enum collection parameter and result schemas`() {
        val openApi = ArcOpenApiGenerator().generate(listOf(ConceptFixtureModule())).openApi
        val schemas = openApi.components.schemas

        mapOf(
            "CustomerName" to ("string" to null),
            "Quantity" to ("integer" to "int32"),
            "OrderId" to ("string" to "uuid"),
            "DeliveryDate" to ("string" to "date"),
            "DeliveryTime" to ("string" to "time"),
            "DeliveryDuration" to ("string" to "duration"),
            "JavaCustomerCode" to ("string" to null),
            "JavaQuantity" to ("integer" to "int32"),
            "JavaOrderId" to ("string" to "uuid"),
            "JavaDeliveryDate" to ("string" to "date"),
            "JavaDeliveryTime" to ("string" to "time"),
            "JavaDeliveryDuration" to ("string" to "duration")
        ).forEach { (name, expected) ->
            val schema = requireNotNull(schemas[name])
            assertEquals(expected.first, schema.type, name)
            assertEquals(expected.second, schema.format, name)
            assertTrue(schema.properties.isNullOrEmpty(), "$name must not be a wrapper object")
        }
        listOf("StateCode", "JavaStateCode").forEach { name ->
            val schema = requireNotNull(schemas[name])
            assertEquals("integer", schema.type, name)
            assertEquals("int32", schema.format, name)
            assertEquals(listOf(0, 17), schema.enum, name)
            assertEquals(listOf("UNKNOWN", "READY"), schema.extensions["x-enumNames"], name)
            assertTrue(schema.properties.isNullOrEmpty(), "$name must not be a wrapper object")
        }

        val commandSchema = requireNotNull(schemas["UseConcepts"])
        assertEquals("#/components/schemas/CustomerName", commandSchema.properties["customerName"]?.`$ref`)
        assertEquals("#/components/schemas/Quantity", commandSchema.properties["quantity"]?.`$ref`)
        assertEquals("#/components/schemas/OrderId", commandSchema.properties["orderId"]?.`$ref`)
        assertEquals("#/components/schemas/DeliveryDate", commandSchema.properties["deliveryDate"]?.`$ref`)
        assertEquals("#/components/schemas/DeliveryTime", commandSchema.properties["deliveryTime"]?.`$ref`)
        assertEquals("#/components/schemas/DeliveryDuration", commandSchema.properties["deliveryDuration"]?.`$ref`)
        assertEquals("#/components/schemas/StateCode", commandSchema.properties["stateCode"]?.`$ref`)
        assertEquals("#/components/schemas/JavaCustomerCode", commandSchema.properties["javaCustomerCode"]?.`$ref`)
        assertEquals("#/components/schemas/JavaQuantity", commandSchema.properties["javaQuantity"]?.`$ref`)
        assertEquals("#/components/schemas/JavaOrderId", commandSchema.properties["javaOrderId"]?.`$ref`)
        assertEquals("#/components/schemas/JavaDeliveryDate", commandSchema.properties["javaDeliveryDate"]?.`$ref`)
        assertEquals("#/components/schemas/JavaDeliveryTime", commandSchema.properties["javaDeliveryTime"]?.`$ref`)
        assertEquals(
            "#/components/schemas/JavaDeliveryDuration",
            commandSchema.properties["javaDeliveryDuration"]?.`$ref`
        )
        assertEquals("#/components/schemas/JavaStateCode", commandSchema.properties["javaStateCode"]?.`$ref`)
        val names = requireNotNull(commandSchema.properties["customerNames"])
        assertEquals("array", names.type)
        assertEquals("#/components/schemas/CustomerName", names.items?.`$ref`)
        val durations = requireNotNull(commandSchema.properties["deliveryDurations"])
        assertEquals("array", durations.type)
        assertEquals("#/components/schemas/DeliveryDuration", durations.items?.`$ref`)

        val query = requireNotNull(openApi.paths["/concepts/query"]?.get)
        val parameters = query.parameters.associateBy { parameter -> parameter.name }
        assertEquals("#/components/schemas/CustomerName", parameters["customerName"]?.schema?.`$ref`)
        assertEquals("#/components/schemas/JavaQuantity", parameters["javaQuantity"]?.schema?.`$ref`)
        assertEquals("#/components/schemas/StateCode", parameters["stateCode"]?.schema?.`$ref`)
        assertEquals("#/components/schemas/DeliveryDuration", parameters["deliveryDuration"]?.schema?.`$ref`)
        assertEquals("array", parameters["customerNames"]?.schema?.type)
        assertEquals("#/components/schemas/CustomerName", parameters["customerNames"]?.schema?.items?.`$ref`)
        assertEquals("array", parameters["deliveryDurations"]?.schema?.type)
        assertEquals(
            "#/components/schemas/DeliveryDuration",
            parameters["deliveryDurations"]?.schema?.items?.`$ref`
        )

        val command = openApi.paths.values.mapNotNull { path -> path.post }
            .single { operation -> operation.operationId == "Executesample.concepts.UseConcepts" }
        val commandResult = requireNotNull(command.responses["200"]?.content?.get("application/json")?.schema)
        assertEquals(
            "#/components/schemas/JavaDeliveryDuration",
            commandResult.properties["response"]?.anyOf?.first()?.`$ref`
        )
        val queryResult = requireNotNull(query.responses["200"]?.content?.get("application/json")?.schema)
        assertEquals(
            "#/components/schemas/DeliveryDuration",
            queryResult.properties["data"]?.anyOf?.first()?.`$ref`
        )
    }

    @Test
    fun `string keyed map properties use recursive additionalProperties without ValueMap metadata`() {
        val strings = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("kotlin.String"),
            TypeShapeDescriptor.value("kotlin.String"),
            MapKeyCodec.STRING
        )
        val numbers = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("kotlin.String"),
            TypeShapeDescriptor.sequence(SequenceKind.LIST, TypeShapeDescriptor.value("kotlin.Int"))
        )
        val nested = TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("kotlin.String"),
            TypeShapeDescriptor.map(
                TypeShapeDescriptor.value("kotlin.String"),
                TypeShapeDescriptor.value("kotlin.Boolean")
            ),
            nullable = true
        )
        val properties = listOf(
            PropertyDescriptor("strings", strings),
            PropertyDescriptor("numbers", numbers),
            PropertyDescriptor("nested", nested)
        )
        val module = object : ArcArtifactModule(
            commandHandlers = emptyList(),
            queryPerformers = emptyList(),
            types = listOf(TypeDescriptor("MapModel", "sample.MapModel", emptyList(), properties))
        ) {}

        val document = ArcOpenApiGenerator().generate(listOf(module))
        val schema = requireNotNull(document.openApi.components.schemas["MapModel"])
        val stringMap = requireNotNull(schema.properties["strings"])
        assertEquals("object", stringMap.type)
        assertEquals("^(?!(?:__proto__|prototype|constructor)$).*$", stringMap.propertyNames.pattern)
        assertEquals("string", (stringMap.additionalProperties as Schema<*>).type)
        val numberMap = requireNotNull(schema.properties["numbers"])
        val numberArray = numberMap.additionalProperties as Schema<*>
        assertEquals("array", numberArray.type)
        assertEquals("integer", numberArray.items.type)
        assertEquals("int32", numberArray.items.format)
        val nullableMap = requireNotNull(schema.properties["nested"])
        assertEquals(2, nullableMap.anyOf.size)
        val nestedMap = nullableMap.anyOf.first()
        assertEquals("object", nestedMap.type)
        val nestedValues = nestedMap.additionalProperties as Schema<*>
        assertEquals("object", nestedValues.type)
        assertEquals("^(?!(?:__proto__|prototype|constructor)$).*$", nestedValues.propertyNames.pattern)
        assertEquals("boolean", (nestedValues.additionalProperties as Schema<*>).type)
        assertFalse(schema.required.orEmpty().contains("nested"))
        assertTrue(schema.required.orEmpty().containsAll(listOf("strings", "numbers")))
        val json = document.json().decodeToString()
        assertTrue(json.contains("additionalProperties"))
        assertFalse(json.contains("ValueMap"))
        assertFalse(json.contains("_entries"))
    }

    @Test
    fun `OpenAPI map metadata rejects floating point values`() {
        listOf("kotlin.Float", "kotlin.Double", "java.lang.Float", "java.lang.Double", "float", "double").forEach {
            typeName ->
            val model = TypeDescriptor(
                "UnsafeFloatingMap",
                "sample.UnsafeFloatingMap",
                emptyList(),
                listOf(
                    PropertyDescriptor(
                        "values",
                        TypeShapeDescriptor.map(
                            TypeShapeDescriptor.value("kotlin.String"),
                            TypeShapeDescriptor.value(typeName)
                        )
                    )
                )
            )
            val module = object : ArcArtifactModule(
                commandHandlers = emptyList(),
                queryPerformers = emptyList(),
                types = listOf(model)
            ) {}

            val exception = assertThrows(IllegalArgumentException::class.java) {
                ArcOpenApiGenerator().generate(listOf(module))
            }
            assertTrue(exception.message.orEmpty().contains("unsupported value leaf '$typeName'"))
        }
    }

    @Test
    fun `OpenAPI map metadata rejects typed model values`() {
        val typedValue = TypeDescriptor("TypedValue", "sample.TypedValue", emptyList())
        val mapModel = TypeDescriptor(
            "UnsafeMapModel",
            "sample.UnsafeMapModel",
            emptyList(),
            listOf(
                PropertyDescriptor(
                    "values",
                    TypeShapeDescriptor.map(
                        TypeShapeDescriptor.value("kotlin.String"),
                        TypeShapeDescriptor.value(typedValue.fullyQualifiedName)
                    )
                )
            )
        )
        val module = object : ArcArtifactModule(
            commandHandlers = emptyList(),
            queryPerformers = emptyList(),
            types = listOf(typedValue, mapModel)
        ) {}

        val exception = assertThrows(IllegalArgumentException::class.java) {
            ArcOpenApiGenerator().generate(listOf(module))
        }

        assertTrue(exception.message.orEmpty().contains("unsupported value leaf 'sample.TypedValue'"))
    }

    @Test
    fun `ordered client and handled command metadata exposes only the client response schema`() {
        val openApi = ArcOpenApiGenerator().generate(listOf(ResponseMetadataFixtureModule())).openApi
        val operation = openApi.paths.values.mapNotNull { path -> path.post }
            .single { candidate -> candidate.operationId == "Execute$CLIENT_AND_HANDLED_COMMAND" }
        val commandResult = requireNotNull(operation.responses["200"]?.content?.get("application/json")?.schema)
        val response = requireNotNull(commandResult.properties["response"])

        assertEquals(
            listOf(CommandResponseValueDisposition.CLIENT, CommandResponseValueDisposition.HANDLED),
            ClientAndHandledCommandHandler.metadata.responseValues.map { value -> value.disposition }
        )
        val responseMembers = requireNotNull(response.anyOf)
        assertEquals(2, responseMembers.size)
        assertEquals(
            listOf(
                Schema<Any>().also { schema -> schema.`$ref` = "#/components/schemas/Widget" },
                Schema<Any>().also { schema -> schema.type = "null" }
            ),
            responseMembers
        )
    }

    @Test
    fun `handled-only command metadata omits the response schema`() {
        val openApi = ArcOpenApiGenerator().generate(listOf(ResponseMetadataFixtureModule())).openApi
        val operation = openApi.paths.values.mapNotNull { path -> path.post }
            .single { candidate -> candidate.operationId == "Execute$HANDLED_ONLY_COMMAND" }
        val commandResult = requireNotNull(operation.responses["200"]?.content?.get("application/json")?.schema)

        assertEquals(listOf(CommandResponseValueDisposition.HANDLED), HandledOnlyCommandHandler.metadata.responseValues.map { it.disposition })
        assertFalse(commandResult.properties.containsKey("response"))
    }

    @Test
    fun `identity endpoint is omitted without a provider while schema route remains`() {
        val paths = ArcOpenApiGenerator().generate(listOf(FixtureModule())).openApi.paths
        assertNull(paths["/.cratis/me"])
        assertNotNull(paths["/.cratis/identity-details/schema"])
    }

    private class ConceptFixtureModule : ArcArtifactModule(
        commandHandlers = listOf(ConceptCommandHandler),
        queryPerformers = listOf(ConceptQueryPerformer),
        types = listOf(TypeDescriptor("UseConcepts", CONCEPT_COMMAND, emptyList(), conceptProperties())),
        enums = listOf(
            EnumDescriptor(
                "FixtureState",
                FIXTURE_STATE,
                emptyList(),
                listOf(EnumMemberDescriptor("UNKNOWN", 0), EnumMemberDescriptor("READY", 17))
            ),
            EnumDescriptor(
                "JavaFixtureState",
                JAVA_FIXTURE_STATE,
                emptyList(),
                listOf(EnumMemberDescriptor("UNKNOWN", 0), EnumMemberDescriptor("READY", 17))
            )
        ),
        concepts = conceptDescriptors()
    )

    private object ConceptCommandHandler : CommandHandler {
        override val commandType: Class<*> = CreateWidget::class.java
        override val metadata = CommandDescriptor(
            "UseConcepts",
            CONCEPT_COMMAND,
            conceptProperties(),
            responseTypeName = JAVA_DELIVERY_DURATION
        )

        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private object ConceptQueryPerformer : QueryPerformer {
        override val descriptor = QueryDescriptor(
            "byConcept",
            "sample.concepts.ConceptReadModel",
            DELIVERY_DURATION,
            conceptParameters(),
            RouteOptions("/concepts/query"),
            explicitPath = "/concepts/query"
        )
        override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
        override suspend fun perform(context: QueryContext): Any? = null
    }

    private class ResponseMetadataFixtureModule : ArcArtifactModule(
        commandHandlers = listOf(ClientAndHandledCommandHandler, HandledOnlyCommandHandler),
        queryPerformers = emptyList(),
        types = listOf(
            TypeDescriptor(
                "Widget",
                WIDGET,
                listOf("io", "cratis", "samples", "widgets"),
                listOf(PropertyDescriptor("name", "kotlin.String"))
            )
        )
    )

    private object ClientAndHandledCommandHandler : CommandHandler {
        override val commandType: Class<*> = ClientAndHandledCommand::class.java
        override val metadata = CommandDescriptor(
            "ClientAndHandledCommand",
            CLIENT_AND_HANDLED_COMMAND,
            responseValues = listOf(
                CommandResponseValueDescriptor(WIDGET, false, CommandResponseValueDisposition.CLIENT),
                CommandResponseValueDescriptor(HANDLED_RESPONSE, false, CommandResponseValueDisposition.HANDLED)
            )
        )

        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private object HandledOnlyCommandHandler : CommandHandler {
        override val commandType: Class<*> = HandledOnlyCommand::class.java
        override val metadata = CommandDescriptor(
            "HandledOnlyCommand",
            HANDLED_ONLY_COMMAND,
            responseValues = listOf(
                CommandResponseValueDescriptor(HANDLED_RESPONSE, false, CommandResponseValueDisposition.HANDLED)
            )
        )

        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private class FixtureModule : ArcArtifactModule(
        commandHandlers = listOf(FixtureCommandHandler),
        queryPerformers = listOf(FixtureQueryPerformer),
        types = listOf(
            TypeDescriptor(
                "CreateWidget",
                CREATE_WIDGET,
                listOf("io", "cratis", "samples", "widgets"),
                listOf(
                    PropertyDescriptor("name", "kotlin.String"),
                    PropertyDescriptor("state", WIDGET_STATE),
                    PropertyDescriptor("labels", "kotlin.collections.List<kotlin.String>", isEnumerable = true, elementTypeName = "kotlin.String"),
                    PropertyDescriptor("note", "kotlin.String", isNullable = true)
                )
            ),
            TypeDescriptor(
                "Widget",
                WIDGET,
                listOf("io", "cratis", "samples", "widgets"),
                listOf(PropertyDescriptor("name", "kotlin.String"))
            )
        ),
        enums = listOf(
            EnumDescriptor(
                "WidgetState",
                WIDGET_STATE,
                listOf("io", "cratis", "samples", "widgets"),
                listOf(EnumMemberDescriptor("OPEN", 10), EnumMemberDescriptor("CLOSED", 20))
            )
        )
    )

    private object FixtureCommandHandler : CommandHandler {
        override val commandType: Class<*> = CreateWidget::class.java
        override val metadata = CommandDescriptor(
            "CreateWidget",
            CREATE_WIDGET,
            listOf(
                PropertyDescriptor("name", "kotlin.String"),
                PropertyDescriptor("state", WIDGET_STATE),
                PropertyDescriptor("labels", "kotlin.collections.List<kotlin.String>", isEnumerable = true, elementTypeName = "kotlin.String"),
                PropertyDescriptor("note", "kotlin.String", isNullable = true)
            ),
            location = listOf("io", "cratis", "samples", "widgets"),
            authorization = AuthorizationMetadata(roles = listOf("admin", "operator")),
            responseTypeName = WIDGET
        )

        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private object FixtureQueryPerformer : QueryPerformer {
        override val descriptor = QueryDescriptor(
            "allWidgets",
            "io.cratis.samples.widgets.Widgets",
            WIDGET,
            listOf(
                ParameterDescriptor("name", "kotlin.String"),
                ParameterDescriptor(
                    "limit",
                    TypeShapeDescriptor.value("kotlin.Int"),
                    QueryParameterSource.CLIENT,
                    hasDefault = true
                ),
                ParameterDescriptor("states", WIDGET_STATE, isNullable = true, isEnumerable = true, elementTypeName = WIDGET_STATE),
                ParameterDescriptor("service", TypeShapeDescriptor.value("java.lang.Object"), QueryParameterSource.SERVICE),
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
                ),
                ParameterDescriptor(
                    "sort",
                    TypeShapeDescriptor.value("org.springframework.data.domain.Sort"),
                    QueryParameterSource.HOST_ADAPTER
                )
            ),
            RouteOptions("/custom/widgets"),
            location = listOf("io", "cratis", "samples", "widgets"),
            authorization = AuthorizationMetadata(allowAnonymous = true),
            explicitPath = "/custom/widgets",
            queryHttpMethod = QueryHttpMethodType.QUERY,
            transport = QueryTransportType.REQUEST_RESPONSE,
            isEnumerable = true,
            supportsPaging = true,
            supportsSorting = true
        )
        override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
        override suspend fun perform(context: QueryContext): Any? = null
    }

    private data class CreateWidget(val name: String)
    private class ClientAndHandledCommand
    private class HandledOnlyCommand

    private companion object {
        const val CONCEPT_COMMAND = "sample.concepts.UseConcepts"
        const val FIXTURE_STATE = "sample.concepts.FixtureState"
        const val JAVA_FIXTURE_STATE = "sample.concepts.JavaFixtureState"
        const val CUSTOMER_NAME = "sample.concepts.CustomerName"
        const val QUANTITY = "sample.concepts.Quantity"
        const val ORDER_ID = "sample.concepts.OrderId"
        const val DELIVERY_DATE = "sample.concepts.DeliveryDate"
        const val DELIVERY_TIME = "sample.concepts.DeliveryTime"
        const val DELIVERY_DURATION = "sample.concepts.DeliveryDuration"
        const val STATE_CODE = "sample.concepts.StateCode"
        const val JAVA_CUSTOMER_CODE = "sample.concepts.JavaCustomerCode"
        const val JAVA_QUANTITY = "sample.concepts.JavaQuantity"
        const val JAVA_ORDER_ID = "sample.concepts.JavaOrderId"
        const val JAVA_DELIVERY_DATE = "sample.concepts.JavaDeliveryDate"
        const val JAVA_DELIVERY_TIME = "sample.concepts.JavaDeliveryTime"
        const val JAVA_DELIVERY_DURATION = "sample.concepts.JavaDeliveryDuration"
        const val JAVA_STATE_CODE = "sample.concepts.JavaStateCode"

        fun conceptDescriptors(): List<ConceptDescriptor> = listOf(
            ConceptDescriptor("CustomerName", CUSTOMER_NAME, "kotlin.String"),
            ConceptDescriptor("Quantity", QUANTITY, "kotlin.Int"),
            ConceptDescriptor("OrderId", ORDER_ID, "java.util.UUID"),
            ConceptDescriptor("DeliveryDate", DELIVERY_DATE, "java.time.LocalDate"),
            ConceptDescriptor("DeliveryTime", DELIVERY_TIME, "java.time.LocalTime"),
            ConceptDescriptor("DeliveryDuration", DELIVERY_DURATION, "java.time.Duration"),
            ConceptDescriptor("StateCode", STATE_CODE, FIXTURE_STATE),
            ConceptDescriptor("JavaCustomerCode", JAVA_CUSTOMER_CODE, "java.lang.String"),
            ConceptDescriptor("JavaQuantity", JAVA_QUANTITY, "java.lang.Integer"),
            ConceptDescriptor("JavaOrderId", JAVA_ORDER_ID, "java.util.UUID"),
            ConceptDescriptor("JavaDeliveryDate", JAVA_DELIVERY_DATE, "java.time.LocalDate"),
            ConceptDescriptor("JavaDeliveryTime", JAVA_DELIVERY_TIME, "java.time.LocalTime"),
            ConceptDescriptor("JavaDeliveryDuration", JAVA_DELIVERY_DURATION, "Duration"),
            ConceptDescriptor("JavaStateCode", JAVA_STATE_CODE, JAVA_FIXTURE_STATE)
        )

        fun conceptProperties(): List<PropertyDescriptor> = conceptDescriptors().map { concept ->
            PropertyDescriptor(concept.name.replaceFirstChar(Char::lowercaseChar), concept.fullyQualifiedName)
        } + listOf(
            PropertyDescriptor(
                "customerNames",
                "kotlin.collections.List<$CUSTOMER_NAME>",
                isEnumerable = true,
                elementTypeName = CUSTOMER_NAME
            ),
            PropertyDescriptor(
                "deliveryDurations",
                "kotlin.collections.List<$DELIVERY_DURATION>",
                isEnumerable = true,
                elementTypeName = DELIVERY_DURATION
            )
        )

        fun conceptParameters(): List<ParameterDescriptor> = conceptDescriptors().map { concept ->
            ParameterDescriptor(concept.name.replaceFirstChar(Char::lowercaseChar), concept.fullyQualifiedName)
        } + listOf(
            ParameterDescriptor(
                "customerNames",
                "kotlin.collections.List<$CUSTOMER_NAME>",
                isEnumerable = true,
                elementTypeName = CUSTOMER_NAME
            ),
            ParameterDescriptor(
                "deliveryDurations",
                "kotlin.collections.List<$DELIVERY_DURATION>",
                isEnumerable = true,
                elementTypeName = DELIVERY_DURATION
            )
        )

        const val CREATE_WIDGET = "io.cratis.samples.widgets.CreateWidget"
        const val WIDGET = "io.cratis.samples.widgets.Widget"
        const val WIDGET_STATE = "io.cratis.samples.widgets.WidgetState"
        const val CLIENT_AND_HANDLED_COMMAND = "io.cratis.samples.responses.ClientAndHandledCommand"
        const val HANDLED_ONLY_COMMAND = "io.cratis.samples.responses.HandledOnlyCommand"
        const val HANDLED_RESPONSE = "io.cratis.samples.responses.HandledResponse"
    }
}
