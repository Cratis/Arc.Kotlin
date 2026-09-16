// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import jakarta.servlet.http.HttpServletRequest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.authentication.AuthenticationConverter
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Converts unsigned x-ms-client-principal headers only after the deployment trust policy accepts.
 * No headers returns null; any partial, untrusted or invalid submission throws a generic failure.
 * Parsing uses a private bounded mapper, independent of application coercion or typing settings.
 */
public class ArcPlatformAuthenticationConverter(private val trust: ArcPlatformIdentityTrust) : AuthenticationConverter {
    override fun convert(request: HttpServletRequest): Authentication? {
        try {
            val headers = HEADER_NAMES.map { name ->
                val values = request.getHeaders(name)
                if (!values.hasMoreElements()) null else values.nextElement().also {
                    require(!values.hasMoreElements())
                }
            }
            if (headers.all { it == null }) return null
            require(trust.isTrusted(request))
            val payload = requireNotNull(headers[0])
            val id = text(requireNotNull(headers[1]))
            text(requireNotNull(headers[2]))
            require(payload.length <= MAX_ENCODED_BYTES)
            val bytes = Base64.getDecoder().decode(payload)
            require(bytes.size <= MAX_DECODED_BYTES && Base64.getEncoder().encodeToString(bytes) == payload)
            val json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val root = mapper.readTree(json)
            require(root.isObject && root.propertyNames().all { it in FIELDS })
            val name = text(requiredString(root, "userDetails"))
            root.get("userId")?.let { text(string(it)) }
            val provider = root.get("identityProvider")?.let(::string)
            val claims = linkedMapOf<String, MutableList<String>>()
            val seen = hashSetOf<Pair<String, String>>()
            root.get("claims")?.let { entries ->
                require(entries.isArray && entries.size() <= MAX_CLAIMS)
                entries.forEach { entry ->
                    require(entry.isObject && entry.propertyNames().toSet() == setOf("typ", "val"))
                    val type = text(requiredString(entry, "typ"))
                    val value = string(requireNotNull(entry.get("val")))
                    require(seen.add(type to value))
                    if (type != "sub" && type != NAME_IDENTIFIER && !type.equals(PROVIDER, ignoreCase = true)) {
                        claims.getOrPut(type, ::arrayListOf).add(value)
                    }
                }
            }
            claims["sub"] = arrayListOf(id)
            claims[NAME_IDENTIFIER] = arrayListOf(id)
            claims.getOrPut(NAME, ::arrayListOf).add(name)
            if (!provider.isNullOrBlank()) claims[PROVIDER] = arrayListOf(provider)
            val roles = linkedSetOf<String>()
            root.get("userRoles")?.let { entries ->
                require(entries.isArray && entries.size() <= MAX_ROLES)
                entries.forEach { require(roles.add(text(string(it)))) }
            }
            val platformRoles = roles.toList()
            // Reference role claims are retained as claims and also participate in role authorization.
            claims[ROLE].orEmpty().forEach { roles.add(text(it)) }
            require(roles.size <= MAX_ROLES)
            if (platformRoles.isNotEmpty()) claims.getOrPut(ROLE, ::arrayListOf).addAll(platformRoles)
            return PlatformAuthentication(name, claims, roles)
        } catch (_: Exception) {
            // Deliberately discard parser causes, offsets, headers and trust-policy exception messages.
            throw BadCredentialsException("Invalid platform identity.")
        }
    }

    private fun requiredString(node: JsonNode, field: String): String = string(requireNotNull(node.get(field)))

    private fun string(node: JsonNode): String {
        require(node.isString)
        return node.stringValue().also { require(it.length <= MAX_STRING && it.none(Char::isISOControl)) }
    }

    private fun text(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_STRING && value.none(Char::isISOControl))
        return value
    }

    private companion object {
        private const val MAX_ENCODED_BYTES = 32768
        private const val MAX_DECODED_BYTES = 24576
        private const val MAX_STRING = 2048
        private const val MAX_CLAIMS = 128
        private const val MAX_ROLES = 64
        private const val PROVIDER = "urn:cratis:arc:identity:provider"
        private const val NAME_IDENTIFIER = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier"
        private const val NAME = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"
        private const val ROLE = "http://schemas.microsoft.com/ws/2008/06/identity/claims/role"
        val HEADER_NAMES = listOf("x-ms-client-principal", "x-ms-client-principal-id", "x-ms-client-principal-name")
        val FIELDS = setOf("identityProvider", "userId", "userDetails", "userRoles", "claims")
        val mapper = JsonMapper.builder(
            JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(4).maxTokenCount(2048).maxDocumentLength(MAX_DECODED_BYTES.toLong())
                    .maxStringLength(MAX_STRING).maxNameLength(256).maxNumberLength(32).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build()
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    }
}

private class PlatformAuthentication(
    private val displayName: String,
    claims: Map<String, List<String>>,
    roles: Set<String>
) : Authentication {
    private val claimSnapshot = Collections.unmodifiableMap(claims.mapValues { (_, values) -> java.util.List.copyOf(values) })
    private val authoritySnapshot = roles.map { SimpleGrantedAuthority("ROLE_$it") }.let { java.util.List.copyOf(it) }
    override fun getName(): String = displayName
    override fun getPrincipal(): Map<String, List<String>> = claimSnapshot
    override fun getAuthorities(): Collection<GrantedAuthority> = authoritySnapshot
    override fun getCredentials(): String = ""
    override fun getDetails(): Map<String, String> = mapOf("authenticationScheme" to "MicrosoftIdentityPlatform")
    override fun isAuthenticated(): Boolean = true
    override fun setAuthenticated(authenticated: Boolean): Unit = throw IllegalArgumentException("Immutable authentication.")
    override fun toString(): String = "PlatformAuthentication[redacted]"
}
