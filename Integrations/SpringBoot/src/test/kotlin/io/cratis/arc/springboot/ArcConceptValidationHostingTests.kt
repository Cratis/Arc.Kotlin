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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [ArcConceptValidationHostingTests.Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
internal class ArcConceptValidationHostingTests {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var fixture: Fixture
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun reset() { fixture.invocations.set(0) }

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `concept bean rejects scalar JSON command before handler and accepts valid value`(language: String) {
        invalid(command(language, ""), language)
        assertEquals(0, fixture.invocations.get())
        valid(command(language, "valid"))
        assertEquals(1, fixture.invocations.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `concept bean participates in HTTP command validate route without handling`(language: String) {
        invalid(command(language, "", "/validate"), language)
        valid(command(language, "valid", "/validate"))
        assertEquals(0, fixture.invocations.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `concept bean rejects bound one-shot query argument before performer`(language: String) {
        assertQuery(language, "query")
    }

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `concept bean rejects observable snapshot argument before opening performer`(language: String) {
        assertQuery(language, "observe")
    }

    private fun assertQuery(language: String, operation: String) {
        fun query(value: String): HttpResponse<String> = send(HttpRequest.newBuilder(uri(
            "/concepts/$language/$operation?code=$value&waitForFirstResult=true&waitForFirstResultTimeout=2"
        )).GET())
        invalid(query(""), language)
        assertEquals(0, fixture.invocations.get())
        assertEquals("valid", valid(query("valid")).path("data").stringValue())
        assertEquals(1, fixture.invocations.get())
    }

    private fun invalid(response: HttpResponse<String>, language: String) {
        assertEquals(400, response.statusCode(), response.body())
        val envelope = mapper.readTree(response.body())
        assertFalse(envelope.path("isSuccess").booleanValue())
        val results = envelope.path("validationResults")
        assertEquals(if (language == "java") 1 else 4, results.size())
        assertEquals("code", results.path(0).path("members").path(0).stringValue())
        assertEquals(if (language == "java") "Java code is required" else "class", results.path(0).path("message").stringValue())
        assertEquals("rule", results.path(0).path("reason").stringValue())
    }
    private fun valid(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        val envelope = mapper.readTree(response.body())
        assertTrue(envelope.path("isSuccess").booleanValue())
        assertEquals(0, envelope.path("validationResults").size())
        return envelope
    }
    private fun command(language: String, value: String, suffix: String = ""): HttpResponse<String> {
        val body = if (language == "java") """{"code":"$value","items":[],"named":{}}""" else """{"code":"$value"}"""
        return send(HttpRequest.newBuilder(uri("/api/concepts/$language$suffix"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)))
    }
    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")
    private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        http.send(builder.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())

    data class KotlinCommand(val code: ArcConceptValidationTests.Code)

    class Fixture {
        val invocations = AtomicInteger()
        fun module(): ArcArtifactModule = object : ArcArtifactModule(
            listOf(handler("kotlin", KotlinCommand::class.java), handler("java", ConceptValidationJavaFixture.Command::class.java)),
            listOf("kotlin", "java").flatMap { language -> listOf(performer(language, false), performer(language, true)) }
        ) {}
        private fun handler(language: String, type: Class<*>): CommandHandler = object : CommandHandler {
            override val commandType = type
            override val metadata = CommandDescriptor(language, type.name,
                location = listOf("concepts"), authorization = AuthorizationMetadata(allowAnonymous = true))
            override suspend fun invoke(context: CommandContext): Any {
                invocations.incrementAndGet()
                return "handled"
            }
        }
        private fun performer(language: String, observable: Boolean): QueryPerformer = object : QueryPerformer {
            private val operation = if (observable) "observe" else "query"
            override val fullyQualifiedName = FullyQualifiedQueryName("concepts.$language.$operation")
            override val descriptor = QueryDescriptor(operation, "concepts.$language", "kotlin.String",
                parameters = listOf(ParameterDescriptor("code", if (language == "java")
                    ConceptValidationJavaFixture.Code::class.java.name else ArcConceptValidationTests.Code::class.java.name)),
                fullyQualifiedName = fullyQualifiedName.value, explicitPath = "/concepts/$language/$operation",
                authorization = AuthorizationMetadata(allowAnonymous = true),
                transport = if (observable) QueryTransportType.OBSERVABLE else QueryTransportType.REQUEST_RESPONSE)
            override suspend fun perform(context: QueryContext): Any {
                invocations.incrementAndGet()
                val concept = context.request.arguments.getValue("code") as io.cratis.arc.concepts.ConceptAs<*>
                val value = requireNotNull(concept.value())
                return if (observable) flowOf(value) else value
            }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    @Import(ArcConceptValidationTests.Rules::class, ConceptValidationJavaFixture.Contributions::class)
    class Application {
        @Bean fun fixture(): Fixture = Fixture()
        @Bean fun conceptsModule(fixture: Fixture): ArcArtifactModule = fixture.module()
    }
}
