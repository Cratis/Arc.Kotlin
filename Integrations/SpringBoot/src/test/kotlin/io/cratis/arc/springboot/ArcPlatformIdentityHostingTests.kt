// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
    classes = [ArcPlatformIdentityHostingTests.Application::class],
    properties = ["cratis.arc.platform-identity.enabled=true"],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
internal class ArcPlatformIdentityHostingTests {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var chains: FilterChainProxy
    @Autowired lateinit var trust: CountingPlatformTrust
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun reset() { trust.calls.set(0) }

    @Test
    fun `real servlet security chain authenticates me once across async dispatch without session replay`() {
        val response = send("/.cratis/me")
        assertEquals(200, response.statusCode())
        val identity = mapper.readTree(response.body())
        assertEquals("canonical-id", identity.path("id").stringValue())
        assertEquals("Ada", identity.path("name").stringValue())
        assertEquals("admin", identity.path("roles")[0].stringValue())
        assertEquals("engineering", identity.path("details").path("department").stringValue())
        assertEquals(1, trust.calls.get())
        assertEquals(1, chains.filterChains.flatMap { it.filters }.count { it is ArcPlatformAuthenticationFilter })
        val cookies = response.headers().allValues("Set-Cookie")
        assertTrue(cookies.any { it.startsWith(".cratis-identity=") })
        assertFalse(cookies.any { it.startsWith("JSESSIONID=") })
        val cachedIdentity = cookies.first { it.startsWith(".cratis-identity=") }.substringBefore(';')
        assertEquals(401, send("/.cratis/me", json = null, cookie = cachedIdentity).statusCode())
    }

    @Test
    fun `real Arc query requires matching role scheme and claim policy after capture`() {
        val good = send("/platform/secured")
        assertEquals(200, good.statusCode())
        assertEquals("canonical-id:MicrosoftIdentityPlatform", mapper.readTree(good.body()).path("data").stringValue())
        assertEquals(403, send("/platform/secured", PLATFORM_JSON.replace("admin", "reader")).statusCode())
        assertEquals(403, send("/platform/secured", PLATFORM_JSON.replace("engineering", "sales")).statusCode())
        assertEquals(403, send("/platform/wrong-scheme").statusCode())
    }

    @Test
    fun `default chain protects metadata and retains CSRF for unsafe methods`() {
        assertEquals(401, send("/.cratis/commands", json = null).statusCode())
        assertEquals(200, send("/.cratis/commands").statusCode())
        assertEquals(403, send("/platform/secured", method = "POST").statusCode())
        assertTrue(chains.filterChains.flatMap { it.filters }.any { it is CsrfFilter })
        val safe = get("/.cratis/commands").with { it.remoteAddr = "192.0.2.10"; it }
        addHeaders(safe)
        val initial = mvc.perform(safe).andReturn()
        val csrf = initial.request.getAttribute(CsrfToken::class.java.name) as CsrfToken
        val value = csrf.token
        val unsafe = post("/platform/secured").session(initial.request.getSession(false) as MockHttpSession)
            .header(csrf.headerName, value).with { it.remoteAddr = "192.0.2.10"; it }
        addHeaders(unsafe)
        // The query is GET-only: reaching 405 proves CSRF accepted the token without weakening policy.
        assertEquals(405, mvc.perform(unsafe).andReturn().response.status)
    }

    @Test
    fun `outside boundary and forged forwarding headers fail on actual chain`() {
        val request = get("/.cratis/me").with { it.remoteAddr = "203.0.113.2"; it }
            .header("X-Forwarded-For", "192.0.2.10").header("Forwarded", "for=192.0.2.10")
        addHeaders(request)
        val response = mvc.perform(request).andReturn().response
        assertEquals(401, response.status)
        assertEquals("Unauthorized", response.contentAsString)
        assertEquals(1, trust.calls.get())
    }

    @Test
    fun `existing authenticated session is preserved but malformed platform submission is terminal`() {
        val auth = UsernamePasswordAuthenticationToken.authenticated("Application user", "", listOf(SimpleGrantedAuthority("ROLE_admin")))
        val session = MockHttpSession()
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl(auth))
        val request = get("/.cratis/me").session(session).with { it.remoteAddr = "192.0.2.10"; it }
        addHeaders(request)
        val initial = mvc.perform(request).andReturn()
        assertTrue(initial.request.isAsyncStarted)
        val response = mvc.perform(asyncDispatch(initial)).andReturn().response
        assertEquals(200, response.status)
        assertEquals("Application user", mapper.readTree(response.contentAsByteArray).path("name").stringValue())
        val malformed = get("/.cratis/me").session(session).header(PLATFORM_HEADERS[0], "invalid")
        assertEquals(401, mvc.perform(malformed).andReturn().response.status)
        val saved = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContextImpl
        assertEquals("Application user", requireNotNull(saved.authentication).name)
    }

    @Test
    fun `duplicate malformed and partial submissions fail generically through container`() {
        val bad = send("/.cratis/me", "{\"userDetails\":false}")
        assertEquals(401, bad.statusCode())
        assertEquals("Unauthorized", bad.body())
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/.cratis/me"))
            .header(PLATFORM_HEADERS[0], Base64.getEncoder().encodeToString(PLATFORM_JSON.toByteArray()))
            .header(PLATFORM_HEADERS[1], "id").header(PLATFORM_HEADERS[1], "duplicate")
            .header(PLATFORM_HEADERS[2], "name").build()
        assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        assertEquals(401, http.send(HttpRequest.newBuilder(request.uri()).header(PLATFORM_HEADERS[2], "partial").build(), HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    private fun addHeaders(request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) {
        request.header(PLATFORM_HEADERS[0], Base64.getEncoder().encodeToString(PLATFORM_JSON.toByteArray()))
            .header(PLATFORM_HEADERS[1], "canonical-id").header(PLATFORM_HEADERS[2], "header-name")
    }

    private fun send(path: String, json: String? = PLATFORM_JSON, method: String = "GET", cookie: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
        if (json != null) request.header(PLATFORM_HEADERS[0], Base64.getEncoder().encodeToString(json.toByteArray()))
            .header(PLATFORM_HEADERS[1], "canonical-id").header(PLATFORM_HEADERS[2], "header-name")
        if (cookie != null) request.header("Cookie", cookie)
        return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean fun platformTrust(): CountingPlatformTrust = CountingPlatformTrust()
        @Bean fun platformArtifacts(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), listOf(
            PlatformQuery("secured", "MicrosoftIdentityPlatform"), PlatformQuery("wrong-scheme", "Bearer")
        )) {}
        @Bean("engineering") fun engineering(): AuthorizationPolicy = AuthorizationPolicy { principal ->
            if (principal.claims.any { it.type == "department" && it.value == "engineering" }) AuthorizationResult.success()
            else AuthorizationResult.failure("Not authorized")
        }
        @Bean fun platformDetails(): IdentityDetailsProvider<PlatformDetails> = object : IdentityDetailsProvider<PlatformDetails> {
            override val detailsType = PlatformDetails::class.java
            override suspend fun provide(context: IdentityProviderContext): IdentityDetails<PlatformDetails> =
                IdentityDetails(true, PlatformDetails(context.claims.firstOrNull { it.type == "department" }?.value ?: "none"))
        }
    }
}

internal data class PlatformDetails(val department: String)

internal class CountingPlatformTrust : ArcPlatformIdentityTrust {
    val calls = AtomicInteger()
    override fun isTrusted(request: jakarta.servlet.http.HttpServletRequest): Boolean {
        calls.incrementAndGet()
        return request.remoteAddr in setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "192.0.2.10")
    }
}

private class PlatformQuery(name: String, scheme: String) : QueryPerformer {
    override val fullyQualifiedName = FullyQualifiedQueryName("platform.$name")
    override val descriptor = QueryDescriptor(name, "platform", String::class.java.name,
        fullyQualifiedName = fullyQualifiedName.value, explicitPath = "/platform/$name",
        authorization = AuthorizationMetadata(policy = "engineering", roles = listOf("admin"), schemes = listOf(scheme)))
    override suspend fun perform(context: QueryContext): Any {
        assertNotNull(context.principal)
        return "${context.principal.id}:${context.principal.authenticationScheme}"
    }
}
