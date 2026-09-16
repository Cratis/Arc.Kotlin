// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.BadCredentialsException

internal class ArcPlatformAuthenticationConverterTests {
    private val converter = ArcPlatformAuthenticationConverter { it.remoteAddr == "192.0.2.10" }

    @Test
    fun `absent headers are anonymous without asking trust policy`() {
        assertNull(ArcPlatformAuthenticationConverter { error("must not run") }.convert(MockHttpServletRequest()))
    }

    @Test
    fun `trusted claims preserve multivalues and provider while replacing reserved identity`() {
        val authentication = requireNotNull(converter.convert(platformRequest("""{
            "identityProvider":"  aad  ","userId":"ignored","userDetails":"Ada","userRoles":["admin"],
            "claims":[{"typ":"sub","val":"spoof"},
              {"typ":"http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier","val":"spoof"},
              {"typ":"URN:CRATIS:ARC:IDENTITY:PROVIDER","val":"spoof"},
              {"typ":"department","val":"engineering"},{"typ":"department","val":"research"},
              {"typ":"urn:cratis:identity:provider-key","val":"retained"}]}
        """)))
        assertEquals("Ada", authentication.name)
        val claims = authentication.principal as Map<*, *>
        assertEquals(listOf("canonical-id"), claims["sub"])
        assertEquals(listOf("canonical-id"), claims["http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier"])
        assertEquals(listOf("  aad  "), claims["urn:cratis:arc:identity:provider"])
        assertEquals(listOf("retained"), claims["urn:cratis:identity:provider-key"])
        assertFalse(claims.containsKey("URN:CRATIS:ARC:IDENTITY:PROVIDER"))
        assertEquals(listOf("engineering", "research"), claims["department"])
        assertEquals(listOf("ROLE_admin"), authentication.authorities.map { it.authority })
        assertEquals(listOf("Ada"), claims["http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"])
        assertEquals(listOf("admin"), claims["http://schemas.microsoft.com/ws/2008/06/identity/claims/role"])
        assertEquals(mapOf("authenticationScheme" to "MicrosoftIdentityPlatform"), authentication.details)
        assertTrue(authentication.isAuthenticated)
        assertEquals("", authentication.credentials)
        assertEquals("PlatformAuthentication[redacted]", authentication.toString())
        assertThrows(IllegalArgumentException::class.java) { authentication.isAuthenticated = false }
    }

    @Test
    fun `blank or absent provider strips colliding reserved claims without inventing provenance`() {
        for (provider in listOf("", "\"identityProvider\":\"  \",")) {
            val result = requireNotNull(converter.convert(platformRequest("""{$provider"userDetails":"Ada",
                "claims":[{"typ":"urn:cratis:arc:identity:provider","val":"forged"}]}""")))
            assertFalse((result.principal as Map<*, *>).containsKey("urn:cratis:arc:identity:provider"))
        }
    }

    @Test
    fun `forwarded headers never change untrusted peer decision`() {
        val request = platformRequest().also {
            it.remoteAddr = "203.0.113.2"
            it.addHeader("X-Forwarded-For", "192.0.2.10")
            it.addHeader("Forwarded", "for=192.0.2.10")
        }
        reject(request)
        assertThrows(BadCredentialsException::class.java) { ArcPlatformAuthenticationConverter { false }.convert(platformRequest()) }
    }

    @Test
    fun `partial duplicate blank and oversized headers fail without secret causes`() {
        for (header in PLATFORM_HEADERS) {
            reject(platformRequest().also { it.removeHeader(header) })
            reject(platformRequest().also { it.addHeader(header, "duplicate-secret") })
            reject(platformRequest().also { it.removeHeader(header); it.addHeader(header, "") })
        }
        reject(platformRequest().also { it.removeHeader(PLATFORM_HEADERS[0]); it.addHeader(PLATFORM_HEADERS[0], "A".repeat(32772)) })
        reject(platformRequest().also { it.removeHeader(PLATFORM_HEADERS[1]); it.addHeader(PLATFORM_HEADERS[1], "x".repeat(2049)) })
    }

    @Test
    fun `strict schema and parser abuse fail generically`() {
        val invalid = listOf(
            "not json", "null", "[]", "{}", "{\"userDetails\":4}",
            "{\"userDetails\":\"Ada\",\"userDetails\":\"Eve\"}",
            "{\"userDetails\":\"Ada\"} {}", "{\"userDetails\":\"Ada\",\"unknown\":true}",
            "{\"userDetails\":\"Ada\",\"claims\":null}",
            "{\"userDetails\":\"Ada\",\"identityProvider\":false}",
            "{\"userDetails\":\"Ada\",\"userRoles\":[1]}",
            "{\"userDetails\":\"Ada\",\"userRoles\":[\"admin\",\"admin\"]}",
            "{\"userDetails\":\"Ada\",\"claims\":[{\"typ\":\"a\",\"val\":[]}]}",
            "{\"userDetails\":\"Ada\",\"claims\":[{\"typ\":\"a\",\"val\":\"v\",\"val\":\"v\"}]}",
            "{\"userDetails\":\"Ada\",\"claims\":[{\"typ\":\"a\",\"val\":\"v\"},{\"typ\":\"a\",\"val\":\"v\"}]}",
            "{\"userDetails\":\"Ada\\nsecret\"}",
            "{\"userDetails\":\"${"x".repeat(2049)}\"}",
            "{\"userDetails\":\"Ada\",\"claims\":${"[".repeat(100)}0${"]".repeat(100)}}",
            "{\"userDetails\":\"Ada\",\"userId\":${"1".repeat(1000)}}",
            "{\"userDetails\":\"Ada\",\"${"x".repeat(257)}\":0}"
        )
        invalid.forEach { reject(platformRequest(it)) }
        for (payload in listOf("%%%", "e30", "e30=,e30=", " e30=", "e31=")) {
            reject(platformRequest().also { it.removeHeader(PLATFORM_HEADERS[0]); it.addHeader(PLATFORM_HEADERS[0], payload) })
        }
    }

    @Test
    fun `non UTF8 payloads and trust failures never expose causes`() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), PLATFORM_JSON.toByteArray(Charsets.UTF_16))) {
            reject(platformRequest().also {
                it.removeHeader(PLATFORM_HEADERS[0])
                it.addHeader(PLATFORM_HEADERS[0], Base64.getEncoder().encodeToString(bytes))
            })
        }
        val failure = assertThrows(BadCredentialsException::class.java) {
            ArcPlatformAuthenticationConverter { error("private trust failure") }.convert(platformRequest())
        }
        assertEquals("Invalid platform identity.", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `standard role claims contribute authorities without losing their claim values`() {
        val json = """{"userDetails":"Ada","userRoles":["member"],"claims":[
            {"typ":"http://schemas.microsoft.com/ws/2008/06/identity/claims/role","val":"admin"}]}"""
        val authentication = requireNotNull(converter.convert(platformRequest(json)))
        assertEquals(listOf("ROLE_member", "ROLE_admin"), authentication.authorities.map { it.authority })
        assertEquals(listOf("admin", "member"), (authentication.principal as Map<*, *>)["http://schemas.microsoft.com/ws/2008/06/identity/claims/role"])
    }

    @Test
    fun `claim role byte and token counts are bounded`() {
        fun claims(count: Int) = (1..count).joinToString(",") { "{\"typ\":\"c$it\",\"val\":\"v\"}" }
        fun roles(count: Int) = (1..count).joinToString(",") { "\"r$it\"" }
        assertTrue(requireNotNull(converter.convert(platformRequest("""{"userDetails":"Ada","claims":[${claims(128)}],"userRoles":[${roles(64)}]}"""))).isAuthenticated)
        reject(platformRequest("""{"userDetails":"Ada","claims":[${claims(129)}]}"""))
        reject(platformRequest("""{"userDetails":"Ada","userRoles":[${roles(65)}]}"""))
        reject(platformRequest("""{"userDetails":"Ada","claims":[${claims(1000)}]}"""))
        reject(platformRequest("""{"userDetails":"Ada","userRoles":[${(1..2200).joinToString(",") { "\"r\"" }}]}"""))
        reject(platformRequest(" " .repeat(24577)))
    }

    private fun reject(request: MockHttpServletRequest) {
        val failure = assertThrows(BadCredentialsException::class.java) { converter.convert(request) }
        assertEquals("Invalid platform identity.", failure.message)
        assertNull(failure.cause)
    }
}

internal val PLATFORM_HEADERS = listOf("x-ms-client-principal", "x-ms-client-principal-id", "x-ms-client-principal-name")
internal const val PLATFORM_JSON = """{"identityProvider":"aad","userDetails":"Ada","userRoles":["admin"],"claims":[{"typ":"department","val":"engineering"}]}"""
internal fun platformRequest(json: String = PLATFORM_JSON): MockHttpServletRequest = MockHttpServletRequest().also {
    it.remoteAddr = "192.0.2.10"
    it.addHeader(PLATFORM_HEADERS[0], Base64.getEncoder().encodeToString(json.toByteArray()))
    it.addHeader(PLATFORM_HEADERS[1], "canonical-id")
    it.addHeader(PLATFORM_HEADERS[2], "header-name")
}
