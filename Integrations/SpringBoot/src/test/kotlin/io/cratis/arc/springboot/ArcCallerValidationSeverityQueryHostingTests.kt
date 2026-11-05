// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.AllowCallerValidationSeverity
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.ObservableQueryHubMessage
import io.cratis.arc.queries.ObservableQueryHubMessageType
import io.cratis.arc.queries.ObservableQuerySubscriptionRequest
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(
    classes = [ArcCallerValidationSeverityQueryHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
internal class ArcCallerValidationSeverityQueryHostingTests {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var transport: ArcObservableQueryTransport

    @Autowired
    lateinit var performers: QueryPerformerRegistry

    @BeforeEach
    fun resetInvocations() {
        QUERY_SEVERITY_INVOCATIONS.clear()
    }

    @Test
    fun `queries and observable queries retain errors above the caller limit`() {
        listOf(false, true).forEach { observable ->
            listOf("GET", "QUERY").forEach { method ->
                listOf("3", "Error").forEach { header ->
                    querySeverityRequest(port, observable, "plain", "Error", header, method).expectQuerySeverity(3)
                }
            }
        }
        assertEquals(0, QUERY_SEVERITY_INVOCATIONS.values.sumOf { it.get() })
    }

    @Test
    fun `queries and observable queries allow warnings within the caller limit`() {
        listOf(false, true).forEach { observable ->
            listOf("2", "Warning").forEach { header ->
                assertEquals(200, querySeverityRequest(port, observable, "plain", "Warning", header).statusCode())
            }
        }
        assertEquals(4, QUERY_SEVERITY_INVOCATIONS.values.sumOf { it.get() })
    }

    @Test
    fun `query artifact opt in replaces the host limit and requires a usable header`() {
        listOf(false, true).forEach { observable ->
            listOf("3", "Error").forEach { header ->
                assertEquals(200, querySeverityRequest(port, observable, "opted-in", "Error", header).statusCode())
            }
            querySeverityRequest(port, observable, "opted-in", "Error").expectQuerySeverity(3)
            querySeverityRequest(port, observable, "stricter", "Warning", "3").expectQuerySeverity(2)
        }
        assertEquals(4, QUERY_SEVERITY_INVOCATIONS.values.sumOf { it.get() })
    }

    @Test
    fun `query warnings as errors floor remains in effect with an artifact opt in`() {
        listOf(false, true).forEach { observable ->
            querySeverityRequest(port, observable, "warnings-as-errors", "Warning", "Error").expectQuerySeverity(2)
        }
        assertEquals(0, QUERY_SEVERITY_INVOCATIONS.values.sumOf { it.get() })
    }

    @Test
    fun `direct observable handshakes apply the artifact caller limit`() {
        listOf("observable-plain", "observable-opted-in").forEach { name ->
            val performer = performers.snapshot().single { it.descriptor.name == name }
            val request = MockHttpServletRequest().apply {
                addHeader("X-Allowed-Severity", "Error")
                addParameter("severity", "Error")
            }
            val handshake = transport.captureHandshake(request, UUID.randomUUID(), performer)
            val captured = transport.createDirectRequest(handshake, performer)
            assertEquals(
                if (name == "observable-plain") ValidationResultSeverity.Warning else ValidationResultSeverity.Error,
                captured.options.allowedValidationSeverity,
            )
        }
    }

    @Test
    fun `observable hub subscriptions apply each artifact caller limit`() {
        listOf("3", "Error").forEach { header ->
            listOf("observable-plain", "observable-opted-in").forEach { name ->
                val performer = performers.snapshot().single { it.descriptor.name == name }
                val request = MockHttpServletRequest().apply { addHeader("X-Allowed-Severity", header) }
                val handshake = transport.captureHandshake(request, UUID.randomUUID())
                val messages = LinkedBlockingQueue<ObservableQueryHubMessage>()
                transport.createHubConnection(UUID.randomUUID().toString(), handshake, messages::offer) {}.use { connection ->
                    assertEquals(HubSubscribeResult.ACCEPTED, transport.subscribe(
                        connection, "query", null,
                        ObservableQuerySubscriptionRequest(performer.fullyQualifiedName.value, mapOf("severity" to "Error")),
                    ))
                    val message = messages.poll(5, TimeUnit.SECONDS)
                    assertEquals(
                        if (name == "observable-plain") ObservableQueryHubMessageType.Error else ObservableQueryHubMessageType.QueryResult,
                        message?.type,
                    )
                }
                assertEquals(
                    if (name == "observable-plain") 0 else 1,
                    QUERY_SEVERITY_INVOCATIONS[performer.fullyQualifiedName.value]?.get() ?: 0,
                )
                QUERY_SEVERITY_INVOCATIONS.clear()
            }
        }
    }

    @Test
    fun `queries and observable queries retain malformed header responses`() {
        listOf(false, true).forEach { observable ->
            listOf("not-a-severity", "4", "-1", "").forEach { header ->
                val response = querySeverityRequest(port, observable, "plain", "Warning", header)
                assertEquals(400, response.statusCode())
                assertEquals("malformedRequest", JsonMapper.builder().build().readTree(response.body())
                    .path("validationResults").path(0).path("reason").asString())
            }
        }
        assertEquals(0, QUERY_SEVERITY_INVOCATIONS.values.sumOf { it.get() })
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun querySeverityModule(): ArcArtifactModule = object : ArcArtifactModule(
            emptyList(),
            listOf(false, true).flatMap { observable ->
                listOf(
                    QuerySeverityPerformer("plain", PlainQuerySeverityArtifact::class.java, observable),
                    QuerySeverityPerformer("opted-in", InheritedQuerySeverityArtifact::class.java, observable),
                    QuerySeverityPerformer("stricter", StricterQuerySeverityArtifact::class.java, observable),
                    QuerySeverityPerformer("warnings-as-errors", InheritedQuerySeverityArtifact::class.java, observable, true),
                )
            },
        ) {}

        @Bean
        fun querySeverityFilter(): QueryFilter = QueryFilter { context ->
            QueryResult.invalid<Any?>(
                context.correlationId,
                listOf(ValidationResult(
                    ValidationResultSeverity.valueOf(context.request.arguments.getValue("severity") as String),
                    "A validation rule was reported.",
                    listOf("severity"),
                )),
            )
        }
    }
}

internal val QUERY_SEVERITY_INVOCATIONS = ConcurrentHashMap<String, AtomicInteger>()

internal fun querySeverityRequest(
    port: Int,
    observable: Boolean,
    artifact: String,
    severity: String,
    header: String? = null,
    method: String = "GET",
): HttpResponse<String> {
    val path = "/api/fixtures/${if (observable) "observable-" else ""}$artifact-query-severity"
    val builder = if (method == "QUERY") {
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .method("QUERY", HttpRequest.BodyPublishers.ofString("""{"arguments":{"severity":"$severity"}}"""))
    } else {
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path?severity=$severity"))
    }
    if (header != null) builder.header("X-Allowed-Severity", header)
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
}

internal fun HttpResponse<String>.expectQuerySeverity(severity: Int) {
    assertEquals(400, statusCode())
    val result = JsonMapper.builder().build().readTree(body())
    assertEquals(false, result.path("isSuccess").asBoolean())
    assertEquals(severity, result.path("validationResults").path(0).path("severity").asInt())
    assertEquals("rule", result.path("validationResults").path(0).path("reason").asString())
}

private class PlainQuerySeverityArtifact

@AllowCallerValidationSeverity(maximum = ValidationResultSeverity.Error)
private open class OptedInQuerySeverityArtifact

private class InheritedQuerySeverityArtifact : OptedInQuerySeverityArtifact()

@AllowCallerValidationSeverity(maximum = ValidationResultSeverity.Information)
private class StricterQuerySeverityArtifact

private class QuerySeverityPerformer(
    artifact: String,
    type: Class<*>,
    private val observable: Boolean,
    treatWarningsAsErrors: Boolean = false,
) : QueryPerformer {
    override val descriptor = QueryDescriptor(
        name = "${if (observable) "observable-" else ""}$artifact",
        declaringTypeName = type.name,
        returnTypeName = "kotlin.String",
        parameters = listOf(ParameterDescriptor("severity", "kotlin.String")),
        authorization = AuthorizationMetadata(allowAnonymous = true),
        explicitPath = "/api/fixtures/${if (observable) "observable-" else ""}$artifact-query-severity",
        transport = if (observable) QueryTransportType.OBSERVABLE else QueryTransportType.REQUEST_RESPONSE,
        treatWarningsAsErrors = treatWarningsAsErrors,
    )
    override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)

    override suspend fun perform(context: QueryContext): Any {
        QUERY_SEVERITY_INVOCATIONS.computeIfAbsent(fullyQualifiedName.value) { AtomicInteger() }.incrementAndGet()
        return if (observable) MutableStateFlow("allowed") else "allowed"
    }
}
