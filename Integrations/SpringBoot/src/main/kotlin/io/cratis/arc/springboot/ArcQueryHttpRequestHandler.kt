// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.http.ArcHttpStatusMapper
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.queries.ObservableQuerySubscriptionRequest
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.InputStream
import java.lang.reflect.Array as ReflectArray
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.core.convert.ConversionService
import org.springframework.http.MediaType
import org.springframework.web.HttpRequestHandler
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncManager
import org.springframework.web.context.request.async.WebAsyncUtils

internal class ArcQueryHttpRequestHandler(
    private val performer: QueryPerformer,
    private val pipeline: QueryPipeline,
    private val serviceResolver: ServiceResolver,
    private val requestBinder: ArcQueryRequestBinder,
    private val objectMapper: ObjectMapper,
    private val coroutineScope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties,
    private val exposeExceptionDetails: Boolean,
    private val principalFactory: ArcPrincipalFactory,
    private val tenantResolution: ArcTenantResolutionService
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val requestStartedAt = System.nanoTime()
        val asyncManager = WebAsyncUtils.getAsyncManager(request)
        if (asyncManager.hasConcurrentResult()) {
            writeConcurrentResult(asyncManager, request, response)
            return
        }

        val correlationId = parseCorrelationId(request.getHeader(properties.correlationHeader))
        prepareResponse(response, correlationId, request.method)
        if (!request.isAsyncSupported) {
            val exception = IllegalStateException(
                "Servlet asynchronous processing is not available for the Arc query endpoint."
            )
            writeResult(response, QueryResult.exception<Any?>(correlationId, exception), exception)
            return
        }

        val capturedRequest = try {
            captureRequest(request, correlationId)
        } catch (exception: ArcRequestBodyTooLargeException) {
            CapturedQueryRequest.Completed(
                correlationId,
                malformed(correlationId),
                statusOverride = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            )
        } catch (_: MalformedQueryRequestException) {
            CapturedQueryRequest.Completed(correlationId, malformed(correlationId))
        } catch (exception: Throwable) {
            CapturedQueryRequest.Completed(
                correlationId,
                QueryResult.exception<Any?>(correlationId, exception),
                exception
            )
        }
        if (asyncManager.asyncWebRequest == null) {
            asyncManager.setAsyncWebRequest(StandardServletAsyncWebRequest(request, response))
        }

        val timeoutException = TimeoutException("Arc query request timed out.")
        val remainingTimeout = properties.requestTimeout.toMillis() -
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt)
        if (remainingTimeout <= 0) {
            writeResult(response, QueryResult.exception<Any?>(correlationId, timeoutException), timeoutException)
            return
        }
        val deferredResult = DeferredResult<HostedQueryResult>(
            remainingTimeout,
            HostedQueryResult(QueryResult.exception<Any?>(correlationId, timeoutException), timeoutException)
        )
        val job = coroutineScope.tryLaunch(start = CoroutineStart.LAZY) {
            try {
                deferredResult.setResult(process(capturedRequest))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                deferredResult.setResult(
                    HostedQueryResult(QueryResult.exception<Any?>(correlationId, exception), exception)
                )
            }
        }
        if (job == null) {
            response.setHeader("Retry-After", properties.overloadRetryAfterSeconds.toString())
            writeResult(
                response,
                overloaded(correlationId),
                statusOverride = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            )
            return
        }
        deferredResult.onTimeout { job.cancel("Arc query request timed out.") }
        deferredResult.onError { exception ->
            job.cancel("Arc query servlet request failed.", exception)
            deferredResult.setResult(
                HostedQueryResult(QueryResult.exception<Any?>(correlationId, exception), exception)
            )
        }
        deferredResult.onCompletion { job.cancel("Arc query servlet request completed.") }

        try {
            asyncManager.startDeferredResultProcessing(deferredResult, correlationId)
            job.start()
        } catch (exception: Throwable) {
            job.cancel("Spring MVC could not start Arc query request processing.", exception)
            writeResult(response, QueryResult.exception<Any?>(correlationId, exception), exception)
        }
    }

    private fun captureRequest(request: HttpServletRequest, correlationId: UUID): CapturedQueryRequest {
        val requiredRoles = performer.descriptor.authorization.roles
            .flatMap { declaration -> declaration.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val principal = principalFactory.create(request, requiredRoles)
        val tenantId = try {
            tenantResolution.resolve(request, principal).value
        } catch (_: TenantResolutionRequiredException) {
            return CapturedQueryRequest.Completed(correlationId, tenantRequired(correlationId))
        } catch (_: TenantAccessDeniedException) {
            return CapturedQueryRequest.Completed(correlationId, QueryResult.unauthorized<Any?>(correlationId))
        }
        val allowedSeverityHeader = request.getHeader(ALLOWED_SEVERITY_HEADER)
        val parsedAllowedSeverity = parseAllowedSeverity(allowedSeverityHeader)
        if (allowedSeverityHeader != null && parsedAllowedSeverity == null) {
            return CapturedQueryRequest.Completed(correlationId, malformed(correlationId))
        }
        val allowedSeverity = parsedAllowedSeverity ?: if (performer.descriptor.treatWarningsAsErrors) {
            ValidationResultSeverity.Information
        } else {
            null
        }
        val queryRequest = when {
            request.method.equals(GET_METHOD, ignoreCase = true) -> requestBinder.fromGet(request, performer)
            request.method.equals(QUERY_METHOD, ignoreCase = true) -> requestBinder.fromQuery(
                performer,
                boundedRequestBody(request, properties.maximumRequestBodyBytes)
            )
            else -> throw IllegalStateException("The query handler received an unsupported HTTP method.")
        }
        return CapturedQueryRequest.Ready(
            correlationId,
            principal,
            tenantId,
            allowedSeverity,
            queryRequest
        )
    }

    private suspend fun process(request: CapturedQueryRequest): HostedQueryResult = when (request) {
        is CapturedQueryRequest.Completed -> HostedQueryResult(
            request.result,
            request.hostException,
            request.statusOverride
        )
        is CapturedQueryRequest.Ready -> {
            val options = QueryExecutionOptions(
                request.correlationId,
                request.principal,
                serviceResolver,
                request.tenantId,
                request.tenantId,
                request.allowedSeverity,
                exposeExceptionDetails
            )
            HostedQueryResult(pipeline.perform(request.request, options))
        }
    }

    private fun writeConcurrentResult(
        asyncManager: WebAsyncManager,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val concurrentResult = asyncManager.concurrentResult
        val correlationId = (concurrentResult as? HostedQueryResult)?.result?.correlationId
            ?: asyncManager.concurrentResultContext?.firstOrNull() as? UUID
            ?: parseCorrelationId(request.getHeader(properties.correlationHeader))
        asyncManager.clearConcurrentResult()
        val hostedResult = when (concurrentResult) {
            is HostedQueryResult -> concurrentResult
            is Throwable -> HostedQueryResult(QueryResult.exception<Any?>(correlationId, concurrentResult), concurrentResult)
            else -> {
                val exception = IllegalStateException("Spring MVC returned an unexpected Arc query result.")
                HostedQueryResult(QueryResult.exception<Any?>(correlationId, exception), exception)
            }
        }
        prepareResponse(response, hostedResult.result.correlationId, request.method)
        writeResult(
            response,
            hostedResult.result,
            hostedResult.hostException,
            hostedResult.statusOverride
        )
    }

    private fun prepareResponse(response: HttpServletResponse, correlationId: UUID, method: String) {
        response.setHeader(properties.correlationHeader, correlationId.toString())
        if (method.equals(QUERY_METHOD, ignoreCase = true)) {
            response.setHeader("Cache-Control", "no-store")
        }
        response.contentType = MediaType.APPLICATION_JSON_VALUE
    }

    private fun writeResult(
        response: HttpServletResponse,
        result: QueryResult<*>,
        hostException: Throwable? = null,
        statusOverride: Int? = null
    ) {
        if (result.hasExceptions) {
            if (hostException != null) {
                logger.error("Arc query request failed. correlationId={}", result.correlationId, hostException)
            } else {
                logger.error(
                    "Arc query request failed. correlationId={} exceptionMessages={} exceptionStackTrace={}",
                    result.correlationId,
                    result.exceptionMessages,
                    result.exceptionStackTrace
                )
            }
        }
        val wireResult = ExceptionDetailRedactor.redact(result, exposeExceptionDetails)
        response.status = statusOverride ?: ArcHttpStatusMapper.map(wireResult).code
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, wireResult)
    }

    private sealed interface CapturedQueryRequest {
        val correlationId: UUID

        data class Ready(
            override val correlationId: UUID,
            val principal: ArcPrincipal,
            val tenantId: String?,
            val allowedSeverity: ValidationResultSeverity?,
            val request: QueryRequest
        ) : CapturedQueryRequest

        data class Completed(
            override val correlationId: UUID,
            val result: QueryResult<*>,
            val hostException: Throwable? = null,
            val statusOverride: Int? = null
        ) : CapturedQueryRequest
    }

    private data class HostedQueryResult(
        val result: QueryResult<*>,
        val hostException: Throwable? = null,
        val statusOverride: Int? = null
    )

    private companion object {
        const val GET_METHOD = "GET"
        const val QUERY_METHOD = "QUERY"
        const val ALLOWED_SEVERITY_HEADER = "X-Allowed-Severity"
        val logger = LoggerFactory.getLogger(ArcQueryHttpRequestHandler::class.java)

        fun overloaded(correlationId: UUID): QueryResult<Any?> = QueryResult.invalid(
            correlationId,
            listOf(
                ValidationResult(
                    ValidationResultSeverity.Error,
                    "The server is temporarily unable to accept more Arc requests."
                )
            )
        )

        fun tenantRequired(correlationId: UUID): QueryResult<Any?> = QueryResult.invalid(
            correlationId,
            listOf(
                ValidationResult(
                    ValidationResultSeverity.Error,
                    "A tenant is required.",
                    reason = ValidationResultReasons.MALFORMED_REQUEST
                )
            )
        )

        fun malformed(correlationId: UUID): QueryResult<Any?> = QueryResult.invalid(
            correlationId,
            listOf(
                ValidationResult(
                    ValidationResultSeverity.Error,
                    "The request is malformed.",
                    reason = ValidationResultReasons.MALFORMED_REQUEST
                )
            )
        )

        fun parseCorrelationId(value: String?): UUID = value
            ?.trim()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()

        fun parseAllowedSeverity(value: String?): ValidationResultSeverity? {
            if (value == null) return null
            val normalized = value.trim()
            normalized.toIntOrNull()?.let { wireValue ->
                return ValidationResultSeverity.entries.firstOrNull { severity -> severity.value() == wireValue }
            }
            return ValidationResultSeverity.entries.firstOrNull { severity ->
                severity.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}

internal class ArcQueryRequestBinder(
    private val objectMapper: ObjectMapper,
    conversionService: ConversionService,
    classLoader: ClassLoader,
    private val ignoredQueryParameterNames: Set<String> = setOf("tenantId")
) {
    private val converter = ArcQueryArgumentConverter(objectMapper, conversionService, classLoader)

    fun fromGet(request: HttpServletRequest, performer: QueryPerformer): QueryRequest =
        fromParameters(request.parameterMap.mapValues { (_, values) -> values.toList() }, performer)

    fun fromParameters(parameters: Map<String, List<String>>, performer: QueryPerformer): QueryRequest {
        val supplied = caseInsensitiveEntries(parameters.entries.map { entry -> entry.key to entry.value })
        val clientParameters = performer.descriptor.parameters.filter { parameter ->
            parameter.source == QueryParameterSource.CLIENT && !isReserved(parameter.name)
        }
        val allowedNames = performer.descriptor.parameters.map { parameter -> parameter.name } +
            RESERVED_FIELDS + TRANSPORT_FIELDS + ignoredQueryParameterNames
        if (supplied.keys.any { name -> allowedNames.none { allowed -> allowed.equals(name, ignoreCase = true) } }) {
            throw MalformedQueryRequestException()
        }
        val arguments = linkedMapOf<String, Any?>()
        clientParameters.forEach { parameter ->
            val values = supplied[parameter.name.lowercase(Locale.ROOT)] ?: return@forEach
            arguments[parameter.name] = converter.fromStrings(values, parameter)
        }

        val page = reservedValue(parameters, PAGE)?.let(::parseNonNegativeInt) ?: 0
        val pageSize = reservedValue(parameters, PAGE_SIZE)?.let(::parseNonNegativeInt) ?: 0
        val sortField = reservedValue(parameters, SORT_BY).orEmpty()
        val sortDirection = parseDirection(reservedValue(parameters, SORT_DIRECTION))
        return QueryRequest(
            performer.fullyQualifiedName,
            arguments,
            QueryPaging(page, pageSize),
            QuerySorting(sortField, sortDirection)
        )
    }

    fun fromSubscription(request: ObservableQuerySubscriptionRequest, performer: QueryPerformer): QueryRequest {
        val arguments = linkedMapOf<String, Any?>()
        val suppliedArguments = caseInsensitiveEntries(request.arguments.orEmpty().entries.map { entry ->
            entry.key to entry.value
        })
        val clientParameters = performer.descriptor.parameters.filter { parameter ->
            parameter.source == QueryParameterSource.CLIENT
        }
        val allowedNames = clientParameters.map { parameter -> parameter.name.lowercase(Locale.ROOT) }
        if (suppliedArguments.keys.any { name -> name !in allowedNames }) {
            throw MalformedQueryRequestException()
        }
        clientParameters.forEach { parameter ->
            if (parameter.name.lowercase(Locale.ROOT) !in suppliedArguments) return@forEach
            val value = suppliedArguments[parameter.name.lowercase(Locale.ROOT)]
            if (value == null) {
                if (!parameter.isNullable) throw MalformedQueryRequestException()
                arguments[parameter.name] = null
            } else {
                arguments[parameter.name] = converter.fromStrings(listOf(value), parameter)
            }
        }
        val page = request.page?.takeIf { it >= 0 } ?: if (request.page == null) 0 else throw MalformedQueryRequestException()
        val pageSize = request.pageSize?.takeIf { it >= 0 } ?: if (request.pageSize == null) 0 else throw MalformedQueryRequestException()
        return QueryRequest(
            performer.fullyQualifiedName,
            arguments,
            QueryPaging(page, pageSize),
            QuerySorting(request.sortBy.orEmpty(), parseDirection(request.sortDirection))
        )
    }

    fun fromQuery(performer: QueryPerformer, input: InputStream): QueryRequest {
        val root: JsonNode = try {
            objectMapper.readTree(input)
        } catch (exception: Exception) {
            if (exception.isArcRequestBodyTooLarge()) throw ArcRequestBodyTooLargeException()
            throw MalformedQueryRequestException()
        } ?: throw MalformedQueryRequestException()
        if (!root.isObject || root.fieldNames().asSequence().any { it !in BODY_FIELDS }) {
            throw MalformedQueryRequestException()
        }

        val argumentsNode = root.get(ARGUMENTS)
        if (argumentsNode != null && !argumentsNode.isNull && !argumentsNode.isObject) {
            throw MalformedQueryRequestException()
        }
        val arguments = linkedMapOf<String, Any?>()
        val clientParameters = performer.descriptor.parameters.filter { parameter ->
            parameter.source == QueryParameterSource.CLIENT
        }
        val suppliedArguments = argumentsNode?.takeUnless(JsonNode::isNull)?.let { node ->
            caseInsensitiveEntries(node.properties().map { entry -> entry.key to entry.value })
        }.orEmpty()
        if (suppliedArguments.keys.any { name ->
                performer.descriptor.parameters.none { it.name.equals(name, ignoreCase = true) }
            }) {
            throw MalformedQueryRequestException()
        }
        clientParameters.forEach { parameter ->
            val value = suppliedArguments[parameter.name.lowercase(Locale.ROOT)] ?: return@forEach
            arguments[parameter.name] = converter.fromJson(value, parameter)
        }

        val pagingNode = root.get(PAGING)
        validateSection(pagingNode, PAGING_FIELDS)
        val page = pagingNode?.takeUnless(JsonNode::isNull)?.get(PAGE)?.let(::parseNonNegativeInt) ?: 0
        val pageSize = pagingNode?.takeUnless(JsonNode::isNull)?.get(PAGE_SIZE)?.let(::parseNonNegativeInt) ?: 0

        val sortingNode = root.get(SORTING)
        validateSection(sortingNode, SORTING_FIELDS)
        val fieldNode = sortingNode?.takeUnless(JsonNode::isNull)?.get(FIELD)
        if (fieldNode != null && !fieldNode.isTextual) throw MalformedQueryRequestException()
        val directionNode = sortingNode?.takeUnless(JsonNode::isNull)?.get(DIRECTION)
        if (directionNode != null && !directionNode.isTextual) throw MalformedQueryRequestException()
        val sortField = fieldNode?.textValue().orEmpty()
        val sortDirection = parseDirection(directionNode?.textValue())

        return QueryRequest(
            performer.fullyQualifiedName,
            arguments,
            QueryPaging(page, pageSize),
            QuerySorting(sortField, sortDirection)
        )
    }

    private fun isReserved(name: String): Boolean = RESERVED_FIELDS.any { reserved ->
        reserved.equals(name, ignoreCase = true)
    }

    private fun <T> caseInsensitiveEntries(entries: List<Pair<String, T>>): Map<String, T> {
        val normalized = linkedMapOf<String, T>()
        entries.forEach { (name, value) ->
            val normalizedName = name.lowercase(Locale.ROOT)
            if (normalized.containsKey(normalizedName)) throw MalformedQueryRequestException()
            normalized[normalizedName] = value
        }
        return normalized
    }

    private fun reservedValue(parameters: Map<String, List<String>>, name: String): String? {
        val entry = parameters.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?: return null
        if (entry.value.size != 1) throw MalformedQueryRequestException()
        return entry.value.single()
    }

    private fun parseNonNegativeInt(value: String): Int = value.toIntOrNull()
        ?.takeIf { it >= 0 }
        ?: throw MalformedQueryRequestException()

    private fun parseNonNegativeInt(value: JsonNode): Int {
        if (!value.isIntegralNumber || !value.canConvertToInt()) throw MalformedQueryRequestException()
        return value.intValue().takeIf { it >= 0 } ?: throw MalformedQueryRequestException()
    }

    private fun parseDirection(value: String?): QuerySortDirection = when {
        value == null || value.equals("asc", ignoreCase = true) || value.equals("ascending", ignoreCase = true) ->
            QuerySortDirection.ASCENDING
        value.equals("desc", ignoreCase = true) || value.equals("descending", ignoreCase = true) ->
            QuerySortDirection.DESCENDING
        else -> throw MalformedQueryRequestException()
    }

    private fun validateSection(section: JsonNode?, allowedFields: Set<String>) {
        if (section == null || section.isNull) return
        if (!section.isObject || section.fieldNames().asSequence().any { it !in allowedFields }) {
            throw MalformedQueryRequestException()
        }
    }

    private companion object {
        const val ARGUMENTS = "arguments"
        const val PAGING = "paging"
        const val SORTING = "sorting"
        const val PAGE = "page"
        const val PAGE_SIZE = "pageSize"
        const val SORT_BY = "sortBy"
        const val SORT_DIRECTION = "sortDirection"
        const val FIELD = "field"
        const val DIRECTION = "direction"
        val BODY_FIELDS = setOf(ARGUMENTS, PAGING, SORTING)
        val PAGING_FIELDS = setOf(PAGE, PAGE_SIZE)
        val SORTING_FIELDS = setOf(FIELD, DIRECTION)
        val RESERVED_FIELDS = setOf(PAGE, PAGE_SIZE, SORT_BY, SORT_DIRECTION)
        val TRANSPORT_FIELDS = setOf("waitForFirstResult", "waitForFirstResultTimeout")
    }
}

private class ArcQueryArgumentConverter(
    private val objectMapper: ObjectMapper,
    private val conversionService: ConversionService,
    private val classLoader: ClassLoader
) {
    fun fromStrings(values: List<String>, descriptor: ParameterDescriptor): Any? {
        val target = parseType(descriptor.typeName)
        return when (target) {
            is TargetType.Collection -> values.map { value -> convertScalar(TextNode(value), target.elementType) }
            is TargetType.Array -> createArray(values.map { value -> convertScalar(TextNode(value), target.elementType) }, target)
            is TargetType.Scalar -> {
                if (values.size != 1) throw MalformedQueryRequestException()
                convertScalar(TextNode(values.single()), target)
            }
        }
    }

    fun fromJson(value: JsonNode, descriptor: ParameterDescriptor): Any? {
        if (value.isNull) {
            if (descriptor.isNullable) return null
            throw MalformedQueryRequestException()
        }
        return when (val target = parseType(descriptor.typeName)) {
            is TargetType.Collection -> {
                val values = if (value.isArray) value.toList() else listOf(value)
                values.map { item -> convertScalar(item, target.elementType) }
            }
            is TargetType.Array -> {
                val values = if (value.isArray) value.toList() else listOf(value)
                createArray(values.map { item -> convertScalar(item, target.elementType) }, target)
            }
            is TargetType.Scalar -> {
                if (value.isArray || value.isObject) throw MalformedQueryRequestException()
                convertScalar(value, target)
            }
        }
    }

    private fun convertScalar(value: JsonNode, target: TargetType.Scalar): Any {
        if (value.isNull) throw MalformedQueryRequestException()
        val targetClass = target.type
        try {
            if (targetClass == String::class.java) {
                if (!value.isTextual) return objectMapper.convertValue(value, String::class.java)
                return value.textValue()
            }
            if (targetClass == Boolean::class.javaObjectType || targetClass == Boolean::class.javaPrimitiveType) {
                if (value.isBoolean) return value.booleanValue()
                if (value.isTextual) {
                    return when {
                        value.textValue().equals("true", ignoreCase = true) -> true
                        value.textValue().equals("false", ignoreCase = true) -> false
                        else -> throw MalformedQueryRequestException()
                    }
                }
                throw MalformedQueryRequestException()
            }
            if (targetClass.isEnum) return convertEnum(value, targetClass)
            if (ConceptAs::class.java.isAssignableFrom(targetClass)) {
                return objectMapper.treeToValue(value, targetClass)
            }
            if (targetClass == LocalTime::class.java) {
                return objectMapper.treeToValue(value, targetClass)
            }
            if (value.isTextual && conversionService.canConvert(String::class.java, targetClass)) {
                return conversionService.convert(value.textValue(), targetClass) ?: throw MalformedQueryRequestException()
            }
            return objectMapper.convertValue(value, targetClass) ?: throw MalformedQueryRequestException()
        } catch (exception: MalformedQueryRequestException) {
            throw exception
        } catch (_: Exception) {
            throw MalformedQueryRequestException()
        }
    }

    private fun convertEnum(value: JsonNode, targetClass: Class<*>): Any {
        val constants = targetClass.enumConstants.filterIsInstance<Enum<*>>()
        if (value.isIntegralNumber || value.isTextual && value.textValue().toIntOrNull() != null) {
            val numeric = if (value.isIntegralNumber) value.intValue() else value.textValue().toInt()
            return constants.firstOrNull { constant ->
                if (constant is ArcEnum) constant.value() == numeric else constant.ordinal == numeric
            } ?: throw MalformedQueryRequestException()
        }
        if (!value.isTextual) throw MalformedQueryRequestException()
        return constants.firstOrNull { constant -> constant.name.equals(value.textValue(), ignoreCase = true) }
            ?: throw MalformedQueryRequestException()
    }

    private fun createArray(values: List<Any>, target: TargetType.Array): Any {
        val array = ReflectArray.newInstance(target.elementType.type, values.size)
        values.forEachIndexed { index, value -> ReflectArray.set(array, index, value) }
        return array
    }

    private fun parseType(typeName: String): TargetType {
        val normalized = typeName.trim().removeSuffix("?")
        PRIMITIVE_ARRAYS[normalized]?.let { return TargetType.Array(TargetType.Scalar(it)) }
        if (normalized.endsWith("[]")) {
            return TargetType.Array(TargetType.Scalar(resolveClass(normalized.removeSuffix("[]"))))
        }
        val genericStart = normalized.indexOf('<')
        if (genericStart >= 0 && normalized.endsWith('>')) {
            val outer = normalized.substring(0, genericStart)
            val inner = normalized.substring(genericStart + 1, normalized.length - 1).trim()
            val element = TargetType.Scalar(resolveClass(inner))
            return when (outer) {
                "kotlin.Array" -> TargetType.Array(element)
                "kotlin.collections.List", "kotlin.collections.MutableList",
                "java.util.List", "java.util.Collection", "java.lang.Iterable" -> TargetType.Collection(element)
                else -> TargetType.Scalar(resolveClass(normalized))
            }
        }
        return TargetType.Scalar(resolveClass(normalized))
    }

    private fun resolveClass(typeName: String): Class<*> = TYPE_ALIASES[typeName]
        ?: try {
            Class.forName(typeName, false, classLoader)
        } catch (_: ClassNotFoundException) {
            throw MalformedQueryRequestException()
        }

    private sealed interface TargetType {
        data class Scalar(val type: Class<*>) : TargetType
        data class Collection(val elementType: Scalar) : TargetType
        data class Array(val elementType: Scalar) : TargetType
    }

    private companion object {
        val TYPE_ALIASES: Map<String, Class<*>> = mapOf(
            "kotlin.String" to String::class.java,
            "java.lang.String" to String::class.java,
            "kotlin.Boolean" to Boolean::class.javaObjectType,
            "java.lang.Boolean" to Boolean::class.javaObjectType,
            "boolean" to Boolean::class.javaPrimitiveType!!,
            "kotlin.Byte" to Byte::class.javaObjectType,
            "java.lang.Byte" to Byte::class.javaObjectType,
            "byte" to Byte::class.javaPrimitiveType!!,
            "kotlin.Short" to Short::class.javaObjectType,
            "java.lang.Short" to Short::class.javaObjectType,
            "short" to Short::class.javaPrimitiveType!!,
            "kotlin.Int" to Int::class.javaObjectType,
            "java.lang.Integer" to Int::class.javaObjectType,
            "int" to Int::class.javaPrimitiveType!!,
            "kotlin.Long" to Long::class.javaObjectType,
            "java.lang.Long" to Long::class.javaObjectType,
            "long" to Long::class.javaPrimitiveType!!,
            "kotlin.Float" to Float::class.javaObjectType,
            "java.lang.Float" to Float::class.javaObjectType,
            "float" to Float::class.javaPrimitiveType!!,
            "kotlin.Double" to Double::class.javaObjectType,
            "java.lang.Double" to Double::class.javaObjectType,
            "double" to Double::class.javaPrimitiveType!!,
            "java.util.UUID" to UUID::class.java,
            "java.time.LocalDate" to LocalDate::class.java,
            "java.time.LocalTime" to LocalTime::class.java,
            "java.time.Instant" to Instant::class.java
        )
        val PRIMITIVE_ARRAYS: Map<String, Class<*>> = mapOf(
            "kotlin.BooleanArray" to Boolean::class.javaPrimitiveType!!,
            "kotlin.ByteArray" to Byte::class.javaPrimitiveType!!,
            "kotlin.ShortArray" to Short::class.javaPrimitiveType!!,
            "kotlin.IntArray" to Int::class.javaPrimitiveType!!,
            "kotlin.LongArray" to Long::class.javaPrimitiveType!!,
            "kotlin.FloatArray" to Float::class.javaPrimitiveType!!,
            "kotlin.DoubleArray" to Double::class.javaPrimitiveType!!
        )
    }
}

internal class MalformedQueryRequestException : IllegalArgumentException()
