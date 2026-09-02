// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.ApiEndpointOptions
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme

private const val SAFE_STRING_MAP_KEY_PATTERN = "^(?!(?:__proto__|prototype|constructor)$).*$"

/** Generates an OpenAPI 3.1 document exclusively from generated Arc artifact metadata. */
public class ArcOpenApiGenerator @JvmOverloads constructor(
    objectMapper: ObjectMapper = ObjectMapper()
) {
    private val writer = objectMapper.copy()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .writerWithDefaultPrettyPrinter()

    /** Generates and serializes one deterministic document snapshot. */
    @JvmOverloads
    public fun generate(
        modules: Iterable<ArcArtifactModule>,
        endpointOptions: ApiEndpointOptions = ApiEndpointOptions(),
        identityProviderPresent: Boolean = false
    ): ArcOpenApiDocument {
        val snapshot = ArtifactSnapshot.from(modules)
        val names = SchemaNames(snapshot)
        val components = Components()
        components.schemas = linkedMapOf()
        components.securitySchemes = linkedMapOf(
            BEARER_SCHEME to SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("JWT Authorization header using the Bearer scheme.")
        )
        addBuiltInSchemas(components)
        addArtifactSchemas(components, snapshot, names)

        val paths = Paths()
        addIdentityPaths(paths, identityProviderPresent)
        addCommandPaths(paths, snapshot.commands, endpointOptions, names)
        addQueryPaths(paths, snapshot.queries, endpointOptions, names)

        val openApi = OpenAPI()
            .openapi("3.1.0")
            .info(Info().title("Arc Application").version("1.0.0"))
            .components(components)
            .paths(paths)
        return ArcOpenApiDocument(openApi, writer.writeValueAsBytes(openApi))
    }

    private fun addArtifactSchemas(components: Components, snapshot: ArtifactSnapshot, names: SchemaNames) {
        snapshot.enums.forEach { descriptor ->
            val schema = enumSchema(descriptor)
            components.addSchemas(names.forType(descriptor.fullyQualifiedName), schema)
        }
        snapshot.concepts.forEach { descriptor ->
            val underlyingEnum = snapshot.enums.singleOrNull { enum ->
                enum.fullyQualifiedName == descriptor.underlyingTypeName
            }
            val schema = underlyingEnum?.let(::enumSchema)
                ?: scalarOrReference(descriptor.underlyingTypeName, names)
            components.addSchemas(names.forType(descriptor.fullyQualifiedName), schema)
        }
        snapshot.types.forEach { descriptor ->
            components.addSchemas(names.forType(descriptor.fullyQualifiedName), modelSchema(descriptor, names))
        }
        snapshot.commands.forEach { descriptor ->
            if (snapshot.types.none { type -> type.fullyQualifiedName == descriptor.typeName }) {
                components.addSchemas(names.forType(descriptor.typeName), propertiesSchema(descriptor.properties, names))
            }
        }
    }

    private fun enumSchema(descriptor: EnumDescriptor): Schema<Any> = integerSchema("int32").also { schema ->
        schema.enum = descriptor.members.map { member -> member.value as Any }
        schema.addExtension("x-enumNames", descriptor.members.map { member -> member.name })
    }

    private fun addCommandPaths(
        paths: Paths,
        commands: List<CommandDescriptor>,
        options: ApiEndpointOptions,
        names: SchemaNames
    ) {
        val namespaceCounts = commands.groupingBy { descriptor ->
            descriptor.location.drop(options.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        commands.forEach { descriptor ->
            val namespace = descriptor.location.drop(options.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.commandRoute(
                descriptor,
                options,
                (namespaceCounts[namespace] ?: 0) > 1
            )
            val responseSchema = commandResultSchema(descriptor, names)
            val execute = Operation()
                .operationId("Execute${descriptor.typeName}")
                .summary("Execute ${descriptor.name}")
                .tags(listOf(tag(descriptor.location, options)))
                .requestBody(jsonRequest(ref(names.forType(descriptor.typeName)), "The ${descriptor.name} command payload"))
                .responses(resultResponses(responseSchema, includeAccepted = false))
            applyAuthorization(execute, descriptor.authorization)
            addOperation(paths, route, HttpVerb.POST, execute)

            val validate = Operation()
                .operationId("Validate${descriptor.typeName}")
                .summary("Validate ${descriptor.name}")
                .tags(listOf(tag(descriptor.location, options)))
                .requestBody(jsonRequest(ref(names.forType(descriptor.typeName)), "The ${descriptor.name} command payload"))
                .responses(resultResponses(ref(COMMAND_RESULT), includeAccepted = false))
            applyAuthorization(validate, descriptor.authorization)
            addOperation(paths, validationRoute(route), HttpVerb.POST, validate)
        }
    }

    private fun addQueryPaths(
        paths: Paths,
        queries: List<QueryDescriptor>,
        options: ApiEndpointOptions,
        names: SchemaNames
    ) {
        val namespaceCounts = queries.groupingBy { descriptor ->
            descriptor.location.drop(options.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        queries.forEach { descriptor ->
            val namespace = descriptor.location.drop(options.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.queryRoute(
                descriptor,
                options,
                (namespaceCounts[namespace] ?: 0) > 1
            )
            val operation = Operation()
                .operationId("Execute${descriptor.fullyQualifiedName}")
                .summary("Execute ${descriptor.name}")
                .tags(listOf(tag(descriptor.location, options)))
                .responses(resultResponses(queryResultSchema(descriptor, names), includeAccepted = true))
            descriptor.parameters
                .filter { parameter -> parameter.source == QueryParameterSource.CLIENT }
                .filterNot { parameter -> parameter.name.lowercase() in RESERVED_QUERY_PARAMETERS }
                .forEach { parameter -> operation.addParametersItem(queryParameter(parameter, names)) }
            addPagingAndSortingParameters(operation, descriptor.supportsPaging, descriptor.supportsSorting)
            applyAuthorization(operation, descriptor.authorization)
            addOperation(paths, route, HttpVerb.GET, operation)
        }
    }

    private fun addIdentityPaths(paths: Paths, identityProviderPresent: Boolean) {
        val schemaOperation = Operation()
            .operationId("ArcIdentityDetailsSchema")
            .summary("Get the Arc identity details schema")
            .tags(listOf("Identity"))
            .responses(ApiResponses().addApiResponse("200", jsonResponse("Identity details schema", objectSchema())))
        addOperation(paths, IDENTITY_SCHEMA_ROUTE, HttpVerb.GET, schemaOperation)
        if (!identityProviderPresent) return

        val identityOperation = Operation()
            .operationId("ArcIdentity")
            .summary("Get the current Arc identity")
            .tags(listOf("Identity"))
            .responses(
                ApiResponses()
                    .addApiResponse("200", jsonResponse("Current identity", ref(IDENTITY)))
                    .addApiResponse("401", jsonResponse("Unauthenticated", ref(IDENTITY)))
                    .addApiResponse("403", jsonResponse("Forbidden", ref(IDENTITY)))
                    .addApiResponse("500", jsonResponse("Internal server error", ref(IDENTITY)))
            )
        applyAuthorization(identityOperation, AuthorizationMetadata())
        addOperation(paths, IDENTITY_ROUTE, HttpVerb.GET, identityOperation)
    }

    private fun modelSchema(descriptor: TypeDescriptor, names: SchemaNames): Schema<Any> {
        val ownSchema = propertiesSchema(descriptor.properties, names)
        descriptor.derivedTypeId?.let { id ->
            ownSchema.addProperty("_derivedTypeId", stringSchema().also { schema -> schema.enum = listOf(id as Any) })
            ownSchema.required = (ownSchema.required.orEmpty() + "_derivedTypeId").distinct()
        }
        val baseType = descriptor.baseTypeName ?: return ownSchema
        return ComposedSchema().also { schema -> schema.allOf = listOf(ref(names.forType(baseType)), ownSchema) }
    }

    private fun propertiesSchema(properties: List<PropertyDescriptor>, names: SchemaNames): Schema<Any> {
        val schema = objectSchema()
        val required = mutableListOf<String>()
        properties.forEach { property ->
            schema.addProperty(property.name, schemaFor(property.shape, names))
            if (!property.isNullable) required.add(property.name)
        }
        if (required.isNotEmpty()) schema.required = required
        return schema
    }

    private fun commandResultSchema(descriptor: CommandDescriptor, names: SchemaNames): Schema<Any> {
        val schema = baseCommandResultSchema()
        descriptor.responseTypeName?.let { responseType ->
            var response = schemaFor(
                responseType,
                descriptor.responseIsEnumerable,
                if (descriptor.responseIsEnumerable) responseType else null,
                true,
                names
            )
            if (descriptor.responseIsEnumerable) {
                response = nullable(ArraySchema().items(schemaFor(responseType, false, null, false, names)))
            }
            schema.addProperty("response", response)
        }
        return schema
    }

    private fun queryResultSchema(descriptor: QueryDescriptor, names: SchemaNames): Schema<Any> {
        val data = schemaFor(
            descriptor.returnTypeName,
            descriptor.isEnumerable,
            if (descriptor.isEnumerable) descriptor.returnTypeName else null,
            true,
            names
        )
        val item = schemaFor(descriptor.returnTypeName, false, null, false, names)
        val changeSet = changeSetSchema(item)
        return baseQueryResultSchema(data, nullable(changeSet))
    }

    private fun schemaFor(shape: TypeShapeDescriptor, names: SchemaNames): Schema<Any> {
        val schema = when (shape.kind) {
            TypeShapeKind.VALUE -> scalarOrReference(requireNotNull(shape.typeName), names)
            TypeShapeKind.SEQUENCE -> ArraySchema().items(schemaFor(requireNotNull(shape.elementShape), names))
            TypeShapeKind.MAP -> mapSchema(shape, names)
        }
        return if (shape.nullable) nullable(schema) else schema
    }

    private fun mapSchema(shape: TypeShapeDescriptor, names: SchemaNames): Schema<Any> {
        val key = requireNotNull(shape.keyShape)
        require(shape.keyCodec == MapKeyCodec.STRING && key.kind == TypeShapeKind.VALUE && !key.nullable &&
            key.typeName in MAP_STRING_TYPE_NAMES
        ) { "OpenAPI map shapes require nonnullable String keys and the STRING key codec." }
        val value = requireNotNull(shape.valueShape)
        requireNonNullableMapEntry(value, "value")
        return objectSchema().also { schema ->
            schema.additionalProperties = schemaFor(value, names)
            schema.propertyNames = stringSchema().also { propertyName ->
                propertyName.pattern = SAFE_STRING_MAP_KEY_PATTERN
            }
        }
    }

    private fun requireNonNullableMapEntry(shape: TypeShapeDescriptor, path: String) {
        require(!shape.nullable) { "OpenAPI map entry path '$path' cannot be nullable." }
        when (shape.kind) {
            TypeShapeKind.VALUE -> require(shape.typeName in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
                "OpenAPI map entry path '$path' has unsupported value leaf '${shape.typeName}'."
            }
            TypeShapeKind.SEQUENCE -> requireNonNullableMapEntry(requireNotNull(shape.elementShape), "$path[]")
            TypeShapeKind.MAP -> {
                val key = requireNotNull(shape.keyShape)
                require(shape.keyCodec == MapKeyCodec.STRING && key.kind == TypeShapeKind.VALUE && !key.nullable &&
                    key.typeName in MAP_STRING_TYPE_NAMES
                ) { "OpenAPI nested map entry path '$path.key' must use nonnullable String keys." }
                requireNonNullableMapEntry(requireNotNull(shape.valueShape), "$path.value")
            }
        }
    }

    private fun schemaFor(
        typeName: String,
        enumerable: Boolean,
        elementTypeName: String?,
        nullable: Boolean,
        names: SchemaNames
    ): Schema<Any> {
        val parsedElement = elementTypeName ?: genericElement(typeName)
        val schema = if (enumerable || parsedElement != null || typeName.endsWith("[]")) {
            val itemType = parsedElement ?: typeName.removeSuffix("[]")
            ArraySchema().items(schemaFor(itemType, false, null, false, names))
        } else {
            scalarOrReference(typeName, names)
        }
        return if (nullable) nullable(schema) else schema
    }

    private fun scalarOrReference(typeName: String, names: SchemaNames): Schema<Any> {
        val normalized = typeName.removeSuffix("?").trim()
        names.referenceFor(normalized)?.let { return ref(it) }
        return when (normalized) {
            "kotlin.String", "java.lang.String", "String", "kotlin.Char", "java.lang.Character", "char" -> stringSchema()
            "java.util.UUID", "kotlin.uuid.Uuid" -> stringSchema("uuid")
            "java.time.LocalDate" -> stringSchema("date")
            "java.time.LocalTime" -> stringSchema("time")
            "java.time.Duration", "Duration" -> stringSchema("duration")
            "java.time.Instant", "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.time.LocalDateTime" ->
                stringSchema("date-time")
            "kotlin.Boolean", "java.lang.Boolean", "boolean" -> booleanSchema()
            "kotlin.Byte", "java.lang.Byte", "byte", "kotlin.Short", "java.lang.Short", "short",
            "kotlin.Int", "java.lang.Integer", "int" -> integerSchema("int32")
            "kotlin.Long", "java.lang.Long", "long" -> integerSchema("int64")
            "kotlin.Float", "java.lang.Float", "float" -> numberSchema("float")
            "kotlin.Double", "java.lang.Double", "double" -> numberSchema("double")
            "java.math.BigDecimal" -> numberSchema("decimal")
            "kotlin.ByteArray", "byte[]" -> stringSchema("byte")
            "kotlin.Any", "java.lang.Object", "Object", "void", "java.lang.Void" -> objectSchema()
            else -> objectSchema().also { schema -> schema.addExtension("x-arc-type", normalized) }
        }
    }

    private fun queryParameter(descriptor: ParameterDescriptor, names: SchemaNames): Parameter = Parameter()
        .name(descriptor.name)
        .`in`("query")
        .required(!descriptor.isNullable && !descriptor.hasDefault)
        .schema(
            schemaFor(
                descriptor.typeName,
                descriptor.isEnumerable,
                descriptor.elementTypeName,
                descriptor.isNullable,
                names
            )
        )

    private fun addPagingAndSortingParameters(
        operation: Operation,
        supportsPaging: Boolean,
        supportsSorting: Boolean
    ) {
        if (supportsPaging) {
            operation.addParametersItem(Parameter().name("page").`in`("query").required(false).schema(integerSchema("int32")))
            operation.addParametersItem(Parameter().name("pageSize").`in`("query").required(false).schema(integerSchema("int32")))
        }
        if (supportsSorting) {
            operation.addParametersItem(Parameter().name("sortBy").`in`("query").required(false).schema(stringSchema()))
            operation.addParametersItem(
                Parameter().name("sortDirection").`in`("query").required(false).schema(
                    stringSchema().also { schema -> schema.enum = listOf("asc", "ascending", "desc", "descending") }
                )
            )
        }
    }

    private fun applyAuthorization(operation: Operation, authorization: AuthorizationMetadata) {
        operation.addExtension("x-allowAnonymous", authorization.allowAnonymous)
        if (authorization.roles.isNotEmpty()) operation.addExtension("x-roles", authorization.roles)
        authorization.policy?.let { policy -> operation.addExtension("x-policy", policy) }
        if (authorization.schemes.isNotEmpty()) operation.addExtension("x-authenticationSchemes", authorization.schemes)
        if (!authorization.allowAnonymous) {
            operation.security = listOf(SecurityRequirement().addList(BEARER_SCHEME))
        }
    }

    private fun addOperation(paths: Paths, route: String, verb: HttpVerb, operation: Operation) {
        val path = paths[route] ?: PathItem().also { item -> paths.addPathItem(route, item) }
        when (verb) {
            HttpVerb.GET -> require(path.get == null) { "Duplicate Arc GET route '$route' in OpenAPI metadata." }.also {
                path.get = operation
            }
            HttpVerb.POST -> require(path.post == null) { "Duplicate Arc POST route '$route' in OpenAPI metadata." }.also {
                path.post = operation
            }
        }
    }

    private fun addBuiltInSchemas(components: Components) {
        components.addSchemas(VALIDATION_RESULT, validationResultSchema())
        components.addSchemas(PAGING_INFO, pagingInfoSchema())
        components.addSchemas(CHANGE_SET, changeSetSchema(objectSchema()))
        components.addSchemas(COMMAND_RESULT, baseCommandResultSchema())
        components.addSchemas(QUERY_RESULT, baseQueryResultSchema(nullable(objectSchema()), nullable(ref(CHANGE_SET))))
        components.addSchemas(IDENTITY, identitySchema())
    }

    private fun validationResultSchema(): Schema<Any> = objectSchema(
        linkedMapOf(
            "severity" to integerSchema("int32").also { schema -> schema.enum = listOf(1, 2, 3) },
            "message" to stringSchema(),
            "members" to ArraySchema().items(stringSchema()),
            "state" to nullable(objectSchema()),
            "reason" to stringSchema(),
            "reasonDetail" to nullable(stringSchema())
        ),
        listOf("severity", "message", "members", "reason")
    )

    private fun pagingInfoSchema(): Schema<Any> = objectSchema(
        linkedMapOf(
            "page" to integerSchema("int32"),
            "size" to integerSchema("int32"),
            "totalItems" to integerSchema("int64"),
            "totalPages" to integerSchema("int32")
        ),
        listOf("page", "size", "totalItems", "totalPages")
    )

    private fun changeSetSchema(item: Schema<Any>): Schema<Any> = objectSchema(
        linkedMapOf(
            "added" to ArraySchema().items(item),
            "replaced" to ArraySchema().items(item),
            "removed" to ArraySchema().items(item)
        ),
        listOf("added", "replaced", "removed")
    )

    private fun baseCommandResultSchema(): Schema<Any> = objectSchema(
        commonResultProperties() + linkedMapOf("authorizationFailureReason" to stringSchema()),
        COMMON_RESULT_REQUIRED + "authorizationFailureReason"
    )

    private fun baseQueryResultSchema(data: Schema<Any>, changeSet: Schema<Any>): Schema<Any> = objectSchema(
        linkedMapOf(
            "correlationId" to stringSchema("uuid"),
            "data" to data,
            "isReady" to booleanSchema(),
            "isAuthorized" to booleanSchema(),
            "validationResults" to ArraySchema().items(ref(VALIDATION_RESULT)),
            "exceptionMessages" to ArraySchema().items(stringSchema()),
            "exceptionStackTrace" to stringSchema(),
            "paging" to ref(PAGING_INFO),
            "changeSet" to changeSet,
            "isValid" to booleanSchema(),
            "hasExceptions" to booleanSchema(),
            "isSuccess" to booleanSchema()
        ),
        listOf(
            "correlationId",
            "isReady",
            "isAuthorized",
            "validationResults",
            "exceptionMessages",
            "exceptionStackTrace",
            "paging",
            "isValid",
            "hasExceptions",
            "isSuccess"
        )
    )

    private fun identitySchema(): Schema<Any> = objectSchema(
        linkedMapOf(
            "id" to stringSchema(),
            "name" to stringSchema(),
            "isAuthenticated" to booleanSchema(),
            "isAuthorized" to booleanSchema(),
            "roles" to ArraySchema().items(stringSchema()),
            "details" to nullable(objectSchema())
        ),
        listOf("id", "name", "isAuthenticated", "isAuthorized", "roles")
    )

    private fun commonResultProperties(): LinkedHashMap<String, Schema<Any>> = linkedMapOf(
        "correlationId" to stringSchema("uuid"),
        "isAuthorized" to booleanSchema(),
        "validationResults" to ArraySchema().items(ref(VALIDATION_RESULT)),
        "exceptionMessages" to ArraySchema().items(stringSchema()),
        "exceptionStackTrace" to stringSchema(),
        "isValid" to booleanSchema(),
        "hasExceptions" to booleanSchema(),
        "isSuccess" to booleanSchema()
    )

    private fun resultResponses(schema: Schema<Any>, includeAccepted: Boolean): ApiResponses {
        val responses = ApiResponses()
            .addApiResponse("200", jsonResponse("Successful Arc result", schema))
            .addApiResponse("400", jsonResponse("Validation error or malformed request", schema))
            .addApiResponse("403", jsonResponse("Forbidden", schema))
            .addApiResponse("500", jsonResponse("Internal server error", schema))
        if (includeAccepted) responses.addApiResponse("202", jsonResponse("Query data is not ready", schema))
        return responses
    }

    private fun jsonRequest(schema: Schema<Any>, description: String): RequestBody = RequestBody()
        .description(description)
        .required(true)
        .content(Content().addMediaType(APPLICATION_JSON, MediaType().schema(schema)))

    private fun jsonResponse(description: String, schema: Schema<Any>): ApiResponse = ApiResponse()
        .description(description)
        .content(Content().addMediaType(APPLICATION_JSON, MediaType().schema(schema)))

    private fun objectSchema(
        properties: Map<String, Schema<Any>> = emptyMap(),
        required: List<String> = emptyList()
    ): Schema<Any> = Schema<Any>().also { schema ->
        schema.type = "object"
        properties.forEach(schema::addProperty)
        if (required.isNotEmpty()) schema.required = required
    }

    private fun stringSchema(format: String? = null): Schema<Any> = Schema<Any>().also { schema ->
        schema.type = "string"
        schema.format = format
    }

    private fun booleanSchema(): Schema<Any> = Schema<Any>().also { schema -> schema.type = "boolean" }

    private fun integerSchema(format: String): Schema<Any> = Schema<Any>().also { schema ->
        schema.type = "integer"
        schema.format = format
    }

    private fun numberSchema(format: String): Schema<Any> = Schema<Any>().also { schema ->
        schema.type = "number"
        schema.format = format
    }

    private fun nullable(schema: Schema<Any>): Schema<Any> = ComposedSchema().also { composed ->
        composed.anyOf = listOf(schema, Schema<Any>().also { nullSchema -> nullSchema.type = "null" })
    }

    private fun ref(name: String): Schema<Any> = Schema<Any>().also { schema -> schema.`$ref` = "#/components/schemas/$name" }

    private fun tag(location: List<String>, options: ApiEndpointOptions): String =
        location.drop(options.segmentsToSkipForRoute).firstOrNull() ?: "Arc"

    private fun validationRoute(route: String): String = if (route == "/") "/validate" else "$route/validate"

    private fun genericElement(typeName: String): String? {
        val start = typeName.indexOf('<')
        val end = typeName.lastIndexOf('>')
        if (start < 0 || end <= start) return null
        val raw = typeName.substring(0, start)
        if (raw !in COLLECTION_TYPES) return null
        return typeName.substring(start + 1, end).trim()
    }

    private enum class HttpVerb { GET, POST }

    private class ArtifactSnapshot(
        val commands: List<CommandDescriptor>,
        val queries: List<QueryDescriptor>,
        val types: List<TypeDescriptor>,
        val enums: List<EnumDescriptor>,
        val concepts: List<ConceptDescriptor>
    ) {
        companion object {
            fun from(modules: Iterable<ArcArtifactModule>): ArtifactSnapshot {
                val ordered = modules.sortedBy { module -> module.javaClass.name }
                return ArtifactSnapshot(
                    ordered.flatMap { module -> module.commandHandlers }.map { handler -> handler.metadata }
                        .distinctBy(CommandDescriptor::typeName).sortedBy(CommandDescriptor::typeName),
                    ordered.flatMap { module -> module.queryPerformers }.map { performer -> performer.descriptor }
                        .distinctBy(QueryDescriptor::fullyQualifiedName).sortedBy(QueryDescriptor::fullyQualifiedName),
                    ordered.flatMap { module -> module.types }.distinctBy(TypeDescriptor::fullyQualifiedName)
                        .sortedBy(TypeDescriptor::fullyQualifiedName),
                    ordered.flatMap { module -> module.enums }.distinctBy(EnumDescriptor::fullyQualifiedName)
                        .sortedBy(EnumDescriptor::fullyQualifiedName),
                    ordered.flatMap { module -> module.concepts }.distinctBy(ConceptDescriptor::fullyQualifiedName)
                        .sortedBy(ConceptDescriptor::fullyQualifiedName)
                )
            }
        }
    }

    private class SchemaNames(snapshot: ArtifactSnapshot) {
        private val names: Map<String, String>
        private val references: Map<String, String>

        init {
            val fullNames = (
                snapshot.types.map(TypeDescriptor::fullyQualifiedName) +
                    snapshot.enums.map(EnumDescriptor::fullyQualifiedName) +
                    snapshot.concepts.map(ConceptDescriptor::fullyQualifiedName) +
                    snapshot.commands.map(CommandDescriptor::typeName)
                ).distinct().sorted()
            val simpleCounts = fullNames.groupingBy(::simpleName).eachCount()
            names = fullNames.associateWith { fullName ->
                val simple = simpleName(fullName)
                if (simpleCounts[simple] == 1 && simple !in BUILT_IN_NAMES) simple else sanitize(fullName)
            }
            references = buildMap {
                names.forEach { (fullName, schemaName) ->
                    put(fullName, schemaName)
                    putIfAbsent(simpleName(fullName), schemaName)
                }
            }
        }

        fun forType(typeName: String): String = references[typeName] ?: sanitize(typeName)

        fun referenceFor(typeName: String): String? = references[typeName]

        companion object {
            private fun simpleName(value: String): String = value.substringAfterLast('.').substringAfterLast('$')
            private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        }
    }

    private companion object {
        const val APPLICATION_JSON = "application/json"
        val MAP_STRING_TYPE_NAMES = setOf("kotlin.String", "java.lang.String", "String")
        val MAP_SAFE_PRIMITIVE_TYPE_NAMES = setOf(
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Int", "kotlin.Short", "kotlin.String",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Integer", "java.lang.Short",
            "java.lang.String", "boolean", "byte", "char", "int", "short", "String", "Boolean"
        )
        const val BEARER_SCHEME = "Bearer"
        const val COMMAND_RESULT = "CommandResult"
        const val QUERY_RESULT = "QueryResult"
        const val VALIDATION_RESULT = "ValidationResult"
        const val PAGING_INFO = "PagingInfo"
        const val CHANGE_SET = "ChangeSet"
        const val IDENTITY = "Identity"
        const val IDENTITY_ROUTE = "/.cratis/me"
        const val IDENTITY_SCHEMA_ROUTE = "/.cratis/identity-details/schema"
        val BUILT_IN_NAMES = setOf(COMMAND_RESULT, QUERY_RESULT, VALIDATION_RESULT, PAGING_INFO, CHANGE_SET, IDENTITY)
        val COLLECTION_TYPES = setOf(
            "kotlin.Array",
            "kotlin.collections.List",
            "kotlin.collections.Collection",
            "kotlin.collections.Iterable",
            "java.util.List",
            "java.util.Collection",
            "java.lang.Iterable"
        )
        val RESERVED_QUERY_PARAMETERS = setOf("page", "pagesize", "sortby", "sortdirection")
        val COMMON_RESULT_REQUIRED = listOf(
            "correlationId",
            "isAuthorized",
            "validationResults",
            "exceptionMessages",
            "exceptionStackTrace",
            "isValid",
            "hasExceptions",
            "isSuccess"
        )
    }
}
