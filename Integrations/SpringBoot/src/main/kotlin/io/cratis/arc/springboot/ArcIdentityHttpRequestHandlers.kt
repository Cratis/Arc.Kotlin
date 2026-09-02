// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.AsyncIdentityDetailsProvider
import io.cratis.arc.identity.AsyncIdentityDetailsProviderAdapter
import io.cratis.arc.identity.IdentityClaim
import io.cratis.arc.identity.IdentityConstants
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import io.cratis.arc.identity.IdentityProviderResult
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID
import kotlin.reflect.full.memberProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.Environment
import org.springframework.http.ResponseCookie
import org.springframework.web.HttpRequestHandler
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncManager
import org.springframework.web.context.request.async.WebAsyncUtils

internal class ArcIdentityHttpRequestHandler(
    private val provider: ResolvedIdentityDetailsProvider,
    private val principalFactory: ArcPrincipalFactory,
    private val objectMapper: ObjectMapper,
    private val coroutineScope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties,
    private val tenantResolution: ArcTenantResolutionService,
    private val environment: Environment
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val asyncManager = WebAsyncUtils.getAsyncManager(request)
        if (asyncManager.hasConcurrentResult()) {
            writeConcurrentResult(asyncManager, response)
            return
        }

        prepareResponse(response)
        if (!request.isAsyncSupported) {
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            return
        }

        val principal = principalFactory.create(request, emptyList())
        val tenantId = try {
            tenantResolution.resolve(request, principal).value
        } catch (_: TenantResolutionRequiredException) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return
        } catch (_: TenantAccessDeniedException) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }
        val captured = CapturedIdentityRequest(
            principal,
            properties.shouldSecureIdentityCookie(environment, request.isSecure),
            tenantId
        )
        if (asyncManager.asyncWebRequest == null) {
            asyncManager.setAsyncWebRequest(StandardServletAsyncWebRequest(request, response))
        }

        val deferredResult = DeferredResult<IdentityHttpResult>(
            properties.requestTimeout.toMillis(),
            IdentityHttpResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        )
        val job = coroutineScope.tryLaunch(start = CoroutineStart.LAZY) {
            try {
                deferredResult.setResult(process(captured))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logger.error("Arc identity request failed.", exception)
                deferredResult.setResult(IdentityHttpResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR))
            }
        }
        if (job == null) {
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            response.setHeader("Retry-After", properties.overloadRetryAfterSeconds.toString())
            objectMapper.writeValue(
                response.outputStream,
                mapOf("error" to "The server is temporarily unable to accept more Arc requests.")
            )
            return
        }
        deferredResult.onTimeout { job.cancel("Arc identity request timed out.") }
        deferredResult.onError { exception ->
            job.cancel("Arc identity servlet request failed.", exception)
            deferredResult.setResult(IdentityHttpResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR))
        }
        deferredResult.onCompletion { job.cancel("Arc identity servlet request completed.") }

        try {
            asyncManager.startDeferredResultProcessing(deferredResult)
            job.start()
        } catch (exception: Throwable) {
            job.cancel("Spring MVC could not start Arc identity request processing.", exception)
            logger.error("Spring MVC could not start Arc identity request processing.", exception)
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        }
    }

    private suspend fun process(request: CapturedIdentityRequest): IdentityHttpResult {
        if (!request.principal.isAuthenticated) {
            return IdentityHttpResult(HttpServletResponse.SC_UNAUTHORIZED)
        }
        val name = request.principal.name?.takeIf(String::isNotBlank) ?: UNKNOWN_IDENTITY
        val claims = request.principal.claims.toMutableList()
        request.tenantId?.let { tenantId ->
            if (claims.none { claim -> claim.type == properties.tenancy.claimType && claim.value == tenantId }) {
                claims.add(IdentityClaim(properties.tenancy.claimType, tenantId))
            }
        }
        val context = IdentityProviderContext(request.principal.id, name, claims)
        val details = provider.provide(context)
        if (!details.isUserAuthorized) {
            return IdentityHttpResult(HttpServletResponse.SC_FORBIDDEN)
        }
        val result = IdentityProviderResult(
            context.id,
            context.name,
            true,
            true,
            request.principal.roles.toList(),
            details.details
        )
        return IdentityHttpResult(
            HttpServletResponse.SC_OK,
            objectMapper.writeValueAsBytes(result),
            request.secureCookie
        )
    }

    private fun writeConcurrentResult(asyncManager: WebAsyncManager, response: HttpServletResponse) {
        val result = asyncManager.concurrentResult
        asyncManager.clearConcurrentResult()
        val identityResult = result as? IdentityHttpResult
            ?: IdentityHttpResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        prepareResponse(response)
        response.status = identityResult.status
        val body = identityResult.body ?: return
        val encodedBody = Base64.getEncoder().encodeToString(body)
        val cookie = ResponseCookie.from(IdentityConstants.IDENTITY_COOKIE_NAME, encodedBody)
            .httpOnly(false)
            .secure(identityResult.isSecure)
            .sameSite("Lax")
            .path("/")
            .build()
        response.addHeader("Set-Cookie", cookie.toString())
        response.outputStream.write(body)
    }

    private fun prepareResponse(response: HttpServletResponse) {
        response.contentType = APPLICATION_JSON
        response.characterEncoding = StandardCharsets.UTF_8.name()
    }

    private data class CapturedIdentityRequest(
        val principal: ArcPrincipal,
        val secureCookie: Boolean,
        val tenantId: String?
    )
    private data class IdentityHttpResult(
        val status: Int,
        val body: ByteArray? = null,
        val isSecure: Boolean = false
    )

    private companion object {
        const val APPLICATION_JSON = "application/json"
        const val UNKNOWN_IDENTITY = "unknown"
        val logger = LoggerFactory.getLogger(ArcIdentityHttpRequestHandler::class.java)
    }
}

internal class ArcIdentitySchemaHttpRequestHandler(
    provider: ResolvedIdentityDetailsProvider?,
    objectMapper: ObjectMapper
) : HttpRequestHandler {
    private val schemaBytes: ByteArray = objectMapper.writeValueAsBytes(
        provider?.let { IdentityDetailsSchemaGenerator(objectMapper).generate(it.detailsType) }
            ?: objectMapper.createObjectNode()
    )

    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        response.contentType = "application/json"
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.outputStream.write(schemaBytes)
    }
}

internal interface ResolvedIdentityDetailsProvider {
    val detailsType: Class<*>
    suspend fun provide(context: IdentityProviderContext): IdentityDetails<Any>
}

internal fun resolveIdentityDetailsProvider(
    coroutineProviders: ObjectProvider<IdentityDetailsProvider<*>>,
    asyncProviders: ObjectProvider<AsyncIdentityDetailsProvider<*>>
): ResolvedIdentityDetailsProvider? {
    val candidates = mutableListOf<Any>()
    coroutineProviders.orderedStream().forEach { provider ->
        if (candidates.none { candidate -> candidate === provider }) candidates.add(provider)
    }
    asyncProviders.orderedStream().forEach { provider ->
        if (candidates.none { candidate -> candidate === provider }) candidates.add(provider)
    }
    if (candidates.size > 1) {
        val names = candidates.map { candidate -> candidate.javaClass.name }.sorted().joinToString(", ")
        throw IllegalStateException(
            "Exactly one Arc identity details provider may be registered; found ${candidates.size}: $names."
        )
    }
    return candidates.singleOrNull()?.let(::resolvedProvider)
}

@Suppress("UNCHECKED_CAST")
private fun resolvedProvider(provider: Any): ResolvedIdentityDetailsProvider {
    val coroutineProvider = when (provider) {
        is IdentityDetailsProvider<*> -> provider as IdentityDetailsProvider<Any>
        is AsyncIdentityDetailsProvider<*> ->
            AsyncIdentityDetailsProviderAdapter(provider as AsyncIdentityDetailsProvider<Any>)
        else -> error("Unsupported Arc identity details provider type.")
    }
    return object : ResolvedIdentityDetailsProvider {
        override val detailsType: Class<*> = coroutineProvider.detailsType
        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<Any> =
            coroutineProvider.provide(context)
    }
}

private class IdentityDetailsSchemaGenerator(private val objectMapper: ObjectMapper) {
    fun generate(type: Class<*>): ObjectNode = schemaFor(objectMapper.typeFactory.constructType(type), linkedSetOf()) as ObjectNode

    private fun schemaFor(type: JavaType, visiting: MutableSet<Class<*>>, nullable: Boolean = false): JsonNode {
        val raw = type.rawClass
        val schema = when {
            raw == String::class.java || raw == Char::class.javaObjectType || raw == Char::class.javaPrimitiveType ->
                typed("string")
            raw == UUID::class.java -> typed("string").put("format", "uuid")
            raw == LocalDate::class.java -> typed("string").put("format", "date")
            raw == LocalTime::class.java -> typed("string").put("format", "time")
            raw == Instant::class.java || raw == LocalDateTime::class.java || raw == OffsetDateTime::class.java ||
                raw == ZonedDateTime::class.java -> typed("string").put("format", "date-time")
            raw == Boolean::class.javaObjectType || raw == Boolean::class.javaPrimitiveType -> typed("boolean")
            raw in INTEGER_TYPES -> typed("integer")
            raw in NUMBER_TYPES || Number::class.java.isAssignableFrom(raw) -> typed("number")
            raw.isEnum -> typed("string").set<ArrayNode>(
                "enum",
                objectMapper.createArrayNode().also { values -> raw.enumConstants.forEach { values.add((it as Enum<*>).name) } }
            )
            type.isArrayType || type.isCollectionLikeType -> typed("array").set<ObjectNode>(
                "items",
                schemaFor(type.contentType ?: objectMapper.typeFactory.constructType(Any::class.java), visiting) as ObjectNode
            )
            Map::class.java.isAssignableFrom(raw) -> typed("object").put("additionalProperties", true)
            else -> objectSchema(type, visiting)
        }
        if (nullable && schema is ObjectNode) {
            val typeNode = schema.remove("type")
            if (typeNode != null) {
                schema.set<ArrayNode>(
                    "type",
                    objectMapper.createArrayNode().add(typeNode.asText()).add("null")
                )
            }
        }
        return schema
    }

    private fun objectSchema(type: JavaType, visiting: MutableSet<Class<*>>): ObjectNode {
        val schema = typed("object")
        val properties = objectMapper.createObjectNode()
        schema.set<ObjectNode>("properties", properties)
        if (!visiting.add(type.rawClass)) return schema
        val kotlinProperties = runCatching { type.rawClass.kotlin.memberProperties.associateBy { it.name } }
            .getOrDefault(emptyMap())
        val required = objectMapper.createArrayNode()
        objectMapper.serializationConfig.introspect(type).findProperties().sortedBy { it.name }.forEach { property ->
            val propertyType = property.primaryType
            val kotlinProperty = kotlinProperties[property.internalName]
            val isNullable = if (propertyType.rawClass.isPrimitive) {
                false
            } else {
                kotlinProperty?.returnType?.isMarkedNullable ?: !property.isRequired
            }
            properties.set<JsonNode>(property.name, schemaFor(propertyType, visiting, isNullable))
            if (!isNullable) required.add(property.name)
        }
        if (!required.isEmpty) schema.set<ArrayNode>("required", required)
        visiting.remove(type.rawClass)
        return schema
    }

    private fun typed(type: String): ObjectNode = objectMapper.createObjectNode().put("type", type)

    private companion object {
        val INTEGER_TYPES = setOf(
            Byte::class.javaPrimitiveType,
            Byte::class.javaObjectType,
            Short::class.javaPrimitiveType,
            Short::class.javaObjectType,
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Long::class.javaPrimitiveType,
            Long::class.javaObjectType,
            java.math.BigInteger::class.java
        )
        val NUMBER_TYPES = setOf(
            Float::class.javaPrimitiveType,
            Float::class.javaObjectType,
            Double::class.javaPrimitiveType,
            Double::class.javaObjectType,
            java.math.BigDecimal::class.java
        )
    }
}
