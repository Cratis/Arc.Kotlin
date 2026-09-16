// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.contracts.fixtures.JavaCustomerCode
import io.cratis.arc.contracts.fixtures.JavaExclusionInput
import io.cratis.arc.contracts.fixtures.KotlinExclusionInput
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidationExclusion
import io.cratis.arc.validation.ConceptValidator
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.test.annotation.DirtiesContext
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [GeneratedConceptExclusionHostingTest.Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
internal class GeneratedConceptExclusionHostingTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    private val module = ContractTestsArcArtifactModule()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @ParameterizedTest
    @ValueSource(strings = ["Kotlin", "Java"])
    fun `generated HTTP command execution and validation exclude only concept category`(language: String) {
        val handler = module.commandHandlers.single { it.commandType.simpleName == "${language}ExclusionCommand" }
        val route = EndpointRouteHelper.commandRoute(handler.metadata)
        for (suffix in listOf("", "/validate")) {
            valid(post(route + suffix, """{"input":{"ignored":"RULE","required":"VALID"}}"""))
            invalid(post(route + suffix, """{"input":{"ignored":"RULE","required":"RULE"}}"""), "input.required", "concept")
            invalid(post(route + suffix, """{"input":{"ignored":"MODEL","required":"VALID"}}"""), "input.ignored.value", "model")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Kotlin", "Java"])
    fun `generated HTTP one shot and observable snapshots retain required concept and model validation`(language: String) {
        for (method in listOf("find", "observe")) {
            val name = "${language}ExclusionView.$method${language}Exclusions"
            val descriptor = module.queryPerformers.single { it.fullyQualifiedName.value.endsWith(".$name") }.descriptor
            val route = EndpointRouteHelper.queryRoute(descriptor)
            fun request(input: String): HttpResponse<String> {
                val value = if (input == "null") "null" else mapper.writeValueAsString(input)
                return send(HttpRequest.newBuilder(uri("$route?waitForFirstResult=true&waitForFirstResultTimeout=2"))
                    .header("Content-Type", "application/json").method("QUERY", HttpRequest.BodyPublishers.ofString(
                        """{"arguments":{"input":$value}}""")))
            }
            valid(request("""{"ignored":"RULE","required":"VALID"}"""))
            invalid(request("""{"ignored":"RULE","required":"RULE"}"""), "input.required", "concept")
            invalid(request("""{"ignored":"MODEL","required":"VALID"}"""), "input.ignored.value", "model")
            valid(request("null"))
            if (language == "Kotlin") valid(get("$route?waitForFirstResult=true&waitForFirstResultTimeout=2"))
        }
    }

    private fun get(path: String) = send(HttpRequest.newBuilder(uri(path)).GET())
    private fun post(path: String, body: String) = send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body)))
    private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")
    private fun send(builder: HttpRequest.Builder): HttpResponse<String> = http.send(builder.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())
    private fun valid(response: HttpResponse<String>) {
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(mapper.readTree(response.body()).path("isSuccess").booleanValue(), response.body())
    }
    private fun invalid(response: HttpResponse<String>, path: String, message: String? = null) {
        assertEquals(400, response.statusCode(), response.body())
        val feedback = mapper.readTree(response.body()).path("validationResults")
        assertTrue(feedback.any { result -> result.path("members").any { it.stringValue() == path } &&
            (message == null || result.path("message").stringValue() == message) }, response.body())
        if (message != null) assertEquals(1, feedback.size(), response.body())
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application {
        // Explicit application conversion exercises typed inputs without broadening Arc HTTP binding.
        @Bean @Order(Ordered.HIGHEST_PRECEDENCE)
        fun exclusionConversionService(mapper: ObjectMapper): ConversionService = DefaultConversionService().apply {
            addConverter(String::class.java, KotlinExclusionInput::class.java) { mapper.readValue(it, KotlinExclusionInput::class.java) }
            addConverter(String::class.java, JavaExclusionInput::class.java) { mapper.readValue(it, JavaExclusionInput::class.java) }
        }
        @Bean fun kotlinExclusion(): ConceptValidationExclusion = ConceptValidationExclusion(KotlinExclusionInput::class.java, "ignored")
        @Bean fun javaExclusion(): ConceptValidationExclusion = ConceptValidationExclusion(JavaExclusionInput::class.java, "ignored")
        @Bean fun conceptRule(): ConceptValidator<JavaCustomerCode> = object : ConceptValidator<JavaCustomerCode> {
            override val conceptType = JavaCustomerCode::class.java
            override fun validate(concept: JavaCustomerCode): List<ValidationResult> =
                if (concept.value() == "RULE") listOf(ValidationResult.error("concept")) else emptyList()
        }
        @Bean fun modelRule(): ModelValidator<JavaCustomerCode> = object : ModelValidator<JavaCustomerCode> {
            override val modelType = JavaCustomerCode::class.java
            override suspend fun validate(model: JavaCustomerCode, context: ModelValidationContext): List<ValidationResult> =
                if (model.value() == "MODEL") listOf(ValidationResult.error("model", listOf("value"))) else emptyList()
        }
    }
}
