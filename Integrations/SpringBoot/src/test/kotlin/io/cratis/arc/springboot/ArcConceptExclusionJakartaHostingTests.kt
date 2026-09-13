// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidationExclusion
import io.cratis.arc.validation.ConceptValidator
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.test.annotation.DirtiesContext
import tools.jackson.databind.ObjectMapper

/** Uses this module's existing real Jakarta provider; ContractTests intentionally has no provider. */
@SpringBootTest(classes = [ArcConceptExclusionJakartaHostingTests.Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
internal class ArcConceptExclusionJakartaHostingTests {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `command and validation routes retain Jakarta on excluded Kotlin and Java concept edges`(language: String) {
        for (suffix in listOf("", "/validate")) {
            fun request(value: String) = send("/api/excluded/$language$suffix", "POST", """{"ignored":$value,"required":"VALID"}""")
            valid(request("\"RULE\""))
            invalid(request("\"\""), "ignored.value")
            invalid(request("null"), "ignored")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `one shot and observable HTTP snapshots retain Jakarta on excluded concept edges`(language: String) {
        for (operation in listOf("query", "observe")) {
            fun request(value: String): HttpResponse<String> {
                val input = mapper.writeValueAsString("""{"ignored":$value,"required":"VALID"}""")
                return send("/excluded/$language/$operation?waitForFirstResult=true&waitForFirstResultTimeout=2", "QUERY",
                    """{"arguments":{"input":$input}}""")
            }
            valid(request("\"RULE\""))
            invalid(request("\"\""), "input.ignored.value")
            invalid(request("null"), "input.ignored")
        }
    }

    private fun send(path: String, method: String, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString())
    private fun valid(response: HttpResponse<String>) {
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(mapper.readTree(response.body()).path("isSuccess").booleanValue(), response.body())
    }
    private fun invalid(response: HttpResponse<String>, member: String) {
        assertEquals(400, response.statusCode(), response.body())
        val feedback = mapper.readTree(response.body()).path("validationResults")
        assertEquals(1, feedback.size(), response.body())
        assertEquals(member, feedback.path(0).path("members").path(0).stringValue(), response.body())
        assertEquals("rule", feedback.path(0).path("reason").stringValue())
    }

    data class KotlinInput(@field:Valid @field:NotNull val ignored: ConceptExclusionJavaInput.Code?, @field:Valid val required: ConceptExclusionJavaInput.Code?)
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        // HTTP query model binding uses an application converter, not a new Arc object-binding contract.
        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        fun exclusionConversionService(mapper: ObjectMapper): ConversionService = DefaultConversionService().apply {
            addConverter(String::class.java, KotlinInput::class.java) { mapper.readValue(it, KotlinInput::class.java) }
            addConverter(String::class.java, ConceptExclusionJavaInput::class.java) { mapper.readValue(it, ConceptExclusionJavaInput::class.java) }
        }
        @Bean fun kotlinExclusion(): ConceptValidationExclusion = ConceptValidationExclusion(KotlinInput::class.java, "ignored")
        @Bean fun javaExclusion(): ConceptValidationExclusion = ConceptValidationExclusion(ConceptExclusionJavaInput::class.java, "ignored")
        @Bean fun rule(): ConceptValidator<ConceptExclusionJavaInput.Code> = object : ConceptValidator<ConceptExclusionJavaInput.Code> {
            override val conceptType = ConceptExclusionJavaInput.Code::class.java
            override fun validate(concept: ConceptExclusionJavaInput.Code): List<ValidationResult> =
                if (concept.value() == "RULE") listOf(ValidationResult.error("concept")) else emptyList()
        }
        @Bean fun module(): ArcArtifactModule {
            val types = mapOf("kotlin" to KotlinInput::class.java, "java" to ConceptExclusionJavaInput::class.java)
            val handlers = types.map { (language, type) -> object : CommandHandler {
                override val commandType = type
                override val metadata = CommandDescriptor(language, type.name, location = listOf("excluded"), authorization = AuthorizationMetadata(allowAnonymous = true))
                override suspend fun invoke(context: CommandContext): Any = "handled"
            } }
            val performers = types.flatMap { (language, type) -> listOf(false, true).map { observable -> object : QueryPerformer {
                private val operation = if (observable) "observe" else "query"
                override val fullyQualifiedName = FullyQualifiedQueryName("excluded.$language.$operation")
                override val descriptor = QueryDescriptor(operation, "excluded.$language", "kotlin.String",
                    parameters = listOf(ParameterDescriptor("input", type.name, validateRecursively = true)),
                    explicitPath = "/excluded/$language/$operation", authorization = AuthorizationMetadata(allowAnonymous = true),
                    transport = if (observable) QueryTransportType.OBSERVABLE else QueryTransportType.REQUEST_RESPONSE)
                override suspend fun perform(context: QueryContext): Any = if (observable) flowOf("data") else "data"
            } } }
            return object : ArcArtifactModule(handlers, performers) {}
        }
    }
}
