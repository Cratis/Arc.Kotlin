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
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.FluentModelValidator
import io.cratis.arc.validation.IgnoreValidation
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import jakarta.validation.constraints.NotBlank
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
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

/** Real Boot provider/HTTP proof; generated Kotlin/Java transport proof additionally lives in the sample TAP gate. */
@SpringBootTest(classes = [ArcIgnoreValidationHostingTests.Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
internal class ArcIgnoreValidationHostingTests {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `POST preflight and QUERY skip getters before Arc and Jakarta while owner and sibling constraints remain`(language: String) {
        kotlinReads.set(0); IgnoreValidationHttpInput.READS.set(0)
        IgnoreValidationHttpInput.OWNER_CALLS.set(0); IgnoreValidationHttpInput.GROUP_CALLS.set(0)
        for (operation in listOf("execute", "validate", "query")) {
            fun send(value: String): HttpResponse<String> {
                val path = if (operation == "query") "/ignore/$language/query" else "/api/ignore/$language" + if (operation == "validate") "/validate" else ""
                val body = if (operation == "query") """{"arguments":{"input":${mapper.writeValueAsString("""{"sibling":"$value"}""")}}}""" else """{"sibling":"$value"}"""
                return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").method(if (operation == "query") "QUERY" else "POST", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            }
            val before = invoked.get()
            for (bad in listOf("", "owner")) {
                val response = send(bad)
                assertEquals(400, response.statusCode(), response.body())
                val feedback = mapper.readTree(response.body()).path("validationResults")
                assertTrue(feedback.size() > 0, response.body())
                if (bad == "owner") assertTrue(feedback.any { it.path("message").asString() == "whole owner remains active" }, response.body())
                assertEquals(before, invoked.get())
            }
            val accepted = send("ok")
            assertEquals(200, accepted.statusCode(), accepted.body())
            assertTrue(mapper.readTree(accepted.body()).path("isSuccess").booleanValue(), accepted.body())
            assertEquals(before + if (operation == "validate") 0 else 1, invoked.get())
        }
        assertEquals(0, kotlinReads.get())
        assertEquals(0, IgnoreValidationHttpInput.READS.get())
        if (language == "java") {
            assertTrue(IgnoreValidationHttpInput.OWNER_CALLS.get() > 0)
            assertTrue(IgnoreValidationHttpInput.GROUP_CALLS.get() > 0)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["kotlin", "java"])
    fun `direct fluent validation skips the same throwing member without pruning declarations`(language: String) {
        kotlinReads.set(0); IgnoreValidationHttpInput.READS.set(0)
        if (language == "kotlin") {
            val rules = object : FluentModelValidator<KotlinInput>(KotlinInput::class.java) {
                init { ruleFor("ignored").notEmpty(); ruleFor("sibling").notEmpty() }
            }
            assertEquals(listOf("sibling"), rules.validate(KotlinInput("")).flatMap { it.members })
            assertEquals(listOf("ignored", "sibling"), rules.rules.map { it.member })
        } else {
            val rules = object : FluentModelValidator<IgnoreValidationHttpInput>(IgnoreValidationHttpInput::class.java) {
                init { ruleFor("ignored").notEmpty(); ruleFor("sibling").notEmpty() }
            }
            assertEquals(listOf("sibling"), rules.validate(IgnoreValidationHttpInput().apply { sibling = "" }).flatMap { it.members })
            assertEquals(listOf("ignored", "sibling"), rules.rules.map { it.member })
        }
        assertEquals(0, kotlinReads.get()); assertEquals(0, IgnoreValidationHttpInput.READS.get())
    }

    class KotlinInput(@field:NotBlank val sibling: String) {
        @get:IgnoreValidation @get:NotBlank val ignored: String get() { kotlinReads.incrementAndGet(); error("Ignored Kotlin HTTP getter read") }
    }
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        fun ignoreConversionService(mapper: ObjectMapper): ConversionService = DefaultConversionService().apply {
            addConverter(String::class.java, KotlinInput::class.java) { mapper.readValue(it, KotlinInput::class.java) }
            addConverter(String::class.java, IgnoreValidationHttpInput::class.java) { mapper.readValue(it, IgnoreValidationHttpInput::class.java) }
        }
        @Bean fun ownerRule(): ModelValidator<KotlinInput> = object : ModelValidator<KotlinInput> {
            override val modelType = KotlinInput::class.java
            override suspend fun validate(model: KotlinInput, context: ModelValidationContext): List<ValidationResult> =
                if (model.sibling == "owner") listOf(ValidationResult.error("whole owner remains active", listOf("ignored"))) else emptyList()
        }
        @Bean fun module(): ArcArtifactModule {
            val types = mapOf("kotlin" to KotlinInput::class.java, "java" to IgnoreValidationHttpInput::class.java)
            val handlers = types.map { (language, type) -> object : CommandHandler {
                override val commandType = type
                override val metadata = CommandDescriptor(language, type.name, location = listOf("ignore"), authorization = AuthorizationMetadata(allowAnonymous = true))
                override suspend fun invoke(context: CommandContext): Any { invoked.incrementAndGet(); return "handled" }
            } }
            val performers = types.map { (language, type) -> object : QueryPerformer {
                override val fullyQualifiedName = FullyQualifiedQueryName("ignore.$language.query")
                override val descriptor = QueryDescriptor("query", "ignore.$language", "kotlin.String",
                    parameters = listOf(ParameterDescriptor("input", type.name, validateRecursively = true)),
                    explicitPath = "/ignore/$language/query", authorization = AuthorizationMetadata(allowAnonymous = true))
                override suspend fun perform(context: QueryContext): Any { invoked.incrementAndGet(); return "handled" }
            } }
            return object : ArcArtifactModule(handlers, performers) { }
        }
    }
    companion object {
        val kotlinReads = AtomicInteger()
        val invoked = AtomicInteger()
    }
}
