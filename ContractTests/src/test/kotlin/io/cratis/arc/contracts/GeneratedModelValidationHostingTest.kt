// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.AuthorizationPolicyRegistry
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry
import io.cratis.arc.contracts.fixtures.JavaQueryDependency
import io.cratis.arc.contracts.fixtures.KotlinQueryDependency
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.springboot.ArcPrincipalFactory
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@SpringBootTest(classes = [GeneratedModelValidationHostingTest.Application::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
internal class GeneratedModelValidationHostingTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var calls: Calls
    @Autowired lateinit var kotlinDependency: KotlinQueryDependency
    @Autowired lateinit var javaDependency: JavaQueryDependency
    private val module = ContractTestsArcArtifactModule()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach fun reset() { calls.paths.clear() }

    @ParameterizedTest
    @ValueSource(strings = ["Kotlin", "Java"])
    fun `generated commands execute and validate model rules over nested map values`(language: String) {
        val handler = module.commandHandlers.single { it.commandType.simpleName == "${language}MapMetadataCommand" }
        val path = EndpointRouteHelper.commandRoute(handler.metadata)
        val rejected = "invalid-${language.lowercase()}"
        for (suffix in listOf("", "/validate")) {
            invalid(post(path + suffix, """{"strings":{"entry":"$rejected"},"numbers":{},"nested":{},"optional":null}"""), "strings.entry")
            valid(post(path + suffix, """{"strings":{"entry":"valid"},"numbers":{},"nested":{},"optional":null}"""))
        }
        assertEquals(List(4) { "strings.entry" }, calls.paths)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Kotlin", "Java"])
    fun `generated one-shot and observable queries reject supplied models before invoking services`(language: String) {
        val oneShot = if (language == "Kotlin") "KotlinQueryReadModel.defaulted" else "JavaQueryReadModel.byId"
        val observable = if (language == "Kotlin") "KotlinQueryReadModel.observeAll" else "JavaQueryReadModel.observeJava"
        val argument = if (language == "Kotlin") "required" else "identifier"
        val rejected = "invalid-${language.lowercase()}"
        val before = if (language == "Kotlin") kotlinDependency.invocationCount else javaDependency.invocationCount
        invalid(get(queryPath(oneShot) + "?$argument=$rejected"), argument)
        invalid(get(queryPath(observable) + "?label=$rejected&waitForFirstResult=true&waitForFirstResultTimeout=2"), "label")
        assertEquals(before, if (language == "Kotlin") kotlinDependency.invocationCount else javaDependency.invocationCount)
        valid(get(queryPath(oneShot) + "?$argument=valid"))
        val observed = valid(get(queryPath(observable) + "?label=valid&waitForFirstResult=true&waitForFirstResultTimeout=2"))
        assertEquals(1, observed.path("data").size())
        assertTrue(observed.path("data").path(0).path("value").stringValue().contains("valid"))
        assertEquals(before + 2, if (language == "Kotlin") kotlinDependency.invocationCount else javaDependency.invocationCount)
        assertEquals(listOf(argument, "label", argument, "label"), calls.paths)
    }

    @Test
    fun `omitted generated defaults execute at invocation but explicitly supplied defaults are validated`() {
        val path = queryPath("KotlinQueryReadModel.defaulted")
        val omitted = valid(get("$path?required=valid"))
        assertEquals("valid|default|default-suffix|2", omitted.path("data").path("value").stringValue())
        assertEquals(listOf("required"), calls.paths)
        calls.paths.clear()
        invalid(get("$path?required=valid&prefix=default"), "prefix")
        assertEquals(listOf("required", "prefix"), calls.paths)
        calls.paths.clear()
        val observable = queryPath("KotlinQueryReadModel.observeDefaulted")
        val observed = valid(get("$observable?waitForFirstResult=true&waitForFirstResultTimeout=2"))
        assertEquals("flow-default", observed.path("data").path(0).path("value").stringValue())
        assertTrue(calls.paths.isEmpty())
        invalid(get("$observable?label=flow-default&waitForFirstResult=true&waitForFirstResultTimeout=2"), "label")
        assertEquals(listOf("label"), calls.paths)
    }

    private fun queryPath(name: String): String = EndpointRouteHelper.queryRoute(
        module.queryPerformers.single { it.fullyQualifiedName.value.endsWith(".$name") }.descriptor)
    private fun get(path: String) = send(HttpRequest.newBuilder(uri(path)).GET())
    private fun post(path: String, body: String) = send(HttpRequest.newBuilder(uri(path))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)))
    private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")
    private fun send(request: HttpRequest.Builder): HttpResponse<String> = http.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())
    private fun invalid(response: HttpResponse<String>, member: String) {
        assertEquals(400, response.statusCode(), response.body())
        val body = mapper.readTree(response.body())
        assertFalse(body.path("isSuccess").booleanValue())
        assertEquals(1, body.path("validationResults").size())
        assertEquals(member, body.path("validationResults").path(0).path("members").path(0).stringValue())
        assertEquals("rule", body.path("validationResults").path(0).path("reason").stringValue())
    }
    private fun valid(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode(), response.body())
        return mapper.readTree(response.body()).also {
            assertTrue(it.path("isSuccess").booleanValue(), response.body())
            assertEquals(0, it.path("validationResults").size())
        }
    }

    class Calls { val paths = CopyOnWriteArrayList<String>() }
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ModelValidationJavaRules::class)
    class Application {
        @Bean fun calls(): Calls = Calls()
        @Bean fun kotlinDependency(): KotlinQueryDependency = KotlinQueryDependency()
        @Bean fun javaDependency(): JavaQueryDependency = JavaQueryDependency()
        @Bean fun principal(): ArcPrincipalFactory = ArcPrincipalFactory { _, _ ->
            ArcPrincipal("model-tester", true, setOf("viewer", "reader", "auditor"), authenticationScheme = "bearer")
        }
        @Bean fun policies(): AuthorizationPolicyRegistry = ConcurrentAuthorizationPolicyRegistry().apply {
            register("catalog", AuthorizationPolicy { AuthorizationResult.success() })
        }
        @Bean fun kotlinModelRule(calls: Calls): ModelValidator<String> = object : ModelValidator<String> {
            override val modelType = String::class.java
            override suspend fun validate(model: String, context: ModelValidationContext): List<ValidationResult> {
                calls.paths.add(context.memberPath)
                return if (model in setOf("invalid-kotlin", "default", "flow-default")) listOf(ValidationResult.error("Kotlin model rejected")) else emptyList()
            }
        }
        @Bean fun contextMustNotBeInput(): ModelValidator<QueryContext> = forbidden(QueryContext::class.java)
        @Bean fun requestMustNotBeInput(): ModelValidator<QueryRequest> = forbidden(QueryRequest::class.java)
        @Bean fun kotlinServiceMustNotBeInput(): ModelValidator<KotlinQueryDependency> = forbidden(KotlinQueryDependency::class.java)
        @Bean fun javaServiceMustNotBeInput(): ModelValidator<JavaQueryDependency> = forbidden(JavaQueryDependency::class.java)
        private fun <T : Any> forbidden(type: Class<T>): ModelValidator<T> = object : ModelValidator<T> {
            override val modelType = type
            override suspend fun validate(model: T, context: ModelValidationContext): List<ValidationResult> =
                throw AssertionError("Infrastructure is not input: ${type.name}")
        }
    }
}
