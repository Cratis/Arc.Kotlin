// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [ArcQueryHostingTests.Application::class],
    properties = [
        "cratis.arc.tenancy.resolvers=query,header",
        "cratis.arc.correlation-header=X-Arc-Correlation"
    ]
)
@AutoConfigureMockMvc
internal class ArcQueryHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun resetFixture() {
        JavaFixtureArcArtifactModule.INVOCATIONS.set(0)
        JavaFixtureArcArtifactModule.capturedQueryContext = null
    }

    @Test
    fun `GET binds exact client arguments repeated collections typed scalars and service arguments`() {
        val identifier = UUID.fromString("45c55173-f17a-4782-bb51-acbfd3b8cd6f")
        val queryId = UUID.fromString("52869aed-a30d-43b7-99f3-9f500299030f")

        execute(
            typedGet(identifier, queryId)
                .queryParam("dependency", "must-not-be-a-client-argument")
                .queryParam("request", "must-not-be-a-client-argument")
                .queryParam("context", "must-not-be-a-client-argument")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data.name").value("alpha"))
            .andExpect(jsonPath("$.data.count").value(7))
            .andExpect(jsonPath("$.data.active").value(true))
            .andExpect(jsonPath("$.data.identifier").value(identifier.toString()))
            .andExpect(jsonPath("$.data.state").value(1))
            .andExpect(jsonPath("$.data.date").value("2025-01-02"))
            .andExpect(jsonPath("$.data.time").value(LOCAL_TIME_NO_FRACTION))
            .andExpect(jsonPath("$.data.times", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.times[0]").value(LOCAL_TIME_NO_FRACTION))
            .andExpect(jsonPath("$.data.times[1]").value(LOCAL_TIME_SEVEN_DIGITS))
            .andExpect(jsonPath("$.data.instant").value("2025-01-02T03:04:05Z"))
            .andExpect(jsonPath("$.data.ids", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.ids[0]").value(11))
            .andExpect(jsonPath("$.data.codes", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.queryId").value(queryId.toString()))
            .andExpect(jsonPath("$.data.service").value("spring-service"))
            .andExpect(jsonPath("$.data.dependency").doesNotExist())
            .andExpect(jsonPath("$.data.request").doesNotExist())
            .andExpect(jsonPath("$.data.context").doesNotExist())
            .andExpect(jsonPath("$.paging.page").value(0))
            .andExpect(jsonPath("$.validationResults").isArray)
            .andExpect(jsonPath("$.exceptionMessages").isArray)
    }

    @Test
    fun `GET binds paging and both sortBy client casings with flexible directions`() {
        val identifier = UUID.randomUUID()
        val queryId = UUID.randomUUID()
        execute(
            typedGet(identifier, queryId)
                .queryParam("page", "2")
                .queryParam("pageSize", "25")
                .queryParam("sortBy", "name")
                .queryParam("sortDirection", "descending")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(25))
            .andExpect(jsonPath("$.data.sortField").value("name"))
            .andExpect(jsonPath("$.data.sortDirection").value("DESCENDING"))

        execute(
            typedGet(identifier, queryId)
                .queryParam("sortby", "date")
                .queryParam("sortDirection", "AsCeNdInG")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sortField").value("date"))
            .andExpect(jsonPath("$.data.sortDirection").value("ASCENDING"))
    }

    @Test
    fun `QUERY body produces equivalent typed request and disables caching`() {
        val identifier = UUID.randomUUID()
        val queryId = UUID.randomUUID()

        execute(query(TYPED_ROUTE).json(typedQueryBody(identifier, queryId)))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.count").value(7))
            .andExpect(jsonPath("$.data.identifier").value(identifier.toString()))
            .andExpect(jsonPath("$.data.date").value("2025-01-02"))
            .andExpect(jsonPath("$.data.time").value(LOCAL_TIME_NO_FRACTION))
            .andExpect(jsonPath("$.data.times[0]").value(LOCAL_TIME_NO_FRACTION))
            .andExpect(jsonPath("$.data.times[1]").value(LOCAL_TIME_SEVEN_DIGITS))
            .andExpect(jsonPath("$.data.instant").value("2025-01-02T03:04:05Z"))
            .andExpect(jsonPath("$.data.ids[1]").value(12))
            .andExpect(jsonPath("$.data.queryId").value(queryId.toString()))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(25))
            .andExpect(jsonPath("$.data.sortField").value("name"))
            .andExpect(jsonPath("$.data.sortDirection").value("DESCENDING"))
            .andExpect(jsonPath("$.data.dependency").doesNotExist())
            .andExpect(jsonPath("$.data.request").doesNotExist())
            .andExpect(jsonPath("$.data.context").doesNotExist())
    }

    @ParameterizedTest
    @ValueSource(strings = [LOCAL_TIME_NO_FRACTION, LOCAL_TIME_SEVEN_DIGITS])
    fun `GET LocalTime scalars and arrays accept Arc wire precision exactly`(time: String) {
        execute(typedGet(UUID.randomUUID(), UUID.randomUUID(), time))
            .andExpectAcceptedLocalTimes(time)
    }

    @ParameterizedTest
    @ValueSource(strings = [LOCAL_TIME_NO_FRACTION, LOCAL_TIME_SEVEN_DIGITS])
    fun `QUERY LocalTime string scalars and arrays accept Arc wire precision exactly`(time: String) {
        execute(query(TYPED_ROUTE).json(typedQueryBody(UUID.randomUUID(), UUID.randomUUID(), time)))
            .andExpectAcceptedLocalTimes(time)
    }

    @ParameterizedTest
    @ValueSource(strings = [LOCAL_TIME_EIGHT_DIGITS, LOCAL_TIME_NINE_DIGITS])
    fun `GET LocalTime scalars and arrays reject precision beyond Arc wire format`(time: String) {
        execute(typedGet(UUID.randomUUID(), UUID.randomUUID(), time))
            .andExpectSafeMalformedQuery()

        execute(
            typedGet(
                UUID.randomUUID(),
                UUID.randomUUID(),
                times = listOf(LOCAL_TIME_NO_FRACTION, time)
            )
        ).andExpectSafeMalformedQuery()
    }

    @ParameterizedTest
    @ValueSource(strings = [LOCAL_TIME_EIGHT_DIGITS, LOCAL_TIME_NINE_DIGITS])
    fun `QUERY LocalTime string scalars and arrays reject precision beyond Arc wire format`(time: String) {
        execute(query(TYPED_ROUTE).json(typedQueryBody(UUID.randomUUID(), UUID.randomUUID(), time)))
            .andExpectSafeMalformedQuery()

        execute(
            query(TYPED_ROUTE).json(
                typedQueryBody(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    times = listOf(LOCAL_TIME_NO_FRACTION, time)
                )
            )
        ).andExpectSafeMalformedQuery()
    }

    @ParameterizedTest
    @ValueSource(strings = ["24:00:00", "03:60:05"])
    fun `GET direct LocalTime argument rejects values outside valid ranges`(time: String) {
        execute(typedGet(UUID.randomUUID(), UUID.randomUUID(), time))
            .andExpectSafeMalformedQuery()
    }

    @ParameterizedTest
    @ValueSource(strings = ["24:00:00", "03:60:05"])
    fun `QUERY direct LocalTime argument rejects values outside valid ranges`(time: String) {
        execute(query(TYPED_ROUTE).json(typedQueryBody(UUID.randomUUID(), UUID.randomUUID(), time)))
            .andExpectSafeMalformedQuery()
    }

    @Test
    fun `malformed body and supplied argument return parser safe malformed validation`() {
        execute(query(TYPED_ROUTE).json("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(content().string(not(containsString("Json"))))

        execute(typedGet(UUID.randomUUID(), UUID.randomUUID()).queryParam("count", "not-a-number"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    @Test
    fun `query authorization uses the captured request principal`() {
        execute(get(SECURED_ROUTE))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.isAuthorized").value(false))

        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            "alice",
            "unused",
            listOf(SimpleGrantedAuthority("ROLE_admin"))
        )
        execute(
            get(SECURED_ROUTE)
                .principal(authentication)
                .with { servletRequest -> servletRequest.addUserRole("admin"); servletRequest }
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isAuthorized").value(true))
    }

    @Test
    fun `correlation tenant and principal are captured once and correlation is echoed`() {
        val correlationId = UUID.fromString("9f540b48-c9bd-40dd-ae5a-1508348ba877")
        val authentication = UsernamePasswordAuthenticationToken.authenticated("ada", "unused", emptyList())
        execute(
            typedGet(UUID.randomUUID(), UUID.randomUUID())
                .header("x-arc-correlation", correlationId.toString())
                .queryParam("tenantId", "tenant-42")
                .header("X-CRATIS-TENANT-ID", "lower-precedence-tenant")
                .principal(authentication)
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Arc-Correlation", correlationId.toString()))
            .andExpect(jsonPath("$.correlationId").value(correlationId.toString()))
            .andExpect(jsonPath("$.data.tenant").value("tenant-42"))
            .andExpect(jsonPath("$.data.principal").value("ada"))

        val context = requireNotNull(JavaFixtureArcArtifactModule.capturedQueryContext)
        assertEquals("tenant-42", context.tenantId)
        assertEquals("tenant-42", context.tenantNamespace)
    }

    @Test
    fun `authenticated tenant claims deny cross tenant selection without disclosing membership`() {
        val principal = ClaimsPrincipal("ada", mapOf("tenant_id" to listOf("tenant-one", "tenant-two")))
        val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, "unused", emptyList())

        execute(
            typedGet(UUID.randomUUID(), UUID.randomUUID())
                .queryParam("tenantId", "tenant-three")
                .principal(authentication)
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.isAuthorized").value(false))
    }

    @Test
    fun `not ready query returns accepted envelope`() {
        execute(get(NOT_READY_ROUTE))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.isReady").value(false))
            .andExpect(jsonPath("$.paging").exists())
    }

    @Test
    fun `query exceptions are logged then redacted into the query envelope`() {
        execute(get(EXPLODING_ROUTE))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.exceptionMessages[0]").value(ExceptionDetailRedactor.REDACTED_MESSAGE))
            .andExpect(jsonPath("$.exceptionStackTrace").value(""))
            .andExpect(content().string(not(containsString("secret-java-query-detail"))))
    }

    @Test
    fun `command POST and query GET coexist at the same route`() {
        execute(get(COEXISTING_ROUTE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.transport").value("query"))

        execute(post(COEXISTING_ROUTE).json("""{"value":"command"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.response.message").value("handled:command"))
        assertEquals(1, JavaFixtureArcArtifactModule.INVOCATIONS.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["value", "VALUE", "Value", "vAlUe"])
    fun `GET and QUERY bind client argument names case insensitively`(argumentName: String) {
        execute(get(VALIDATED_ROUTE).queryParam(argumentName, "accepted"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.value").value("accepted"))

        execute(query(VALIDATED_ROUTE).json("""{"arguments":{"$argumentName":"accepted"}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.value").value("accepted"))
    }

    @Test
    fun `GET and QUERY reject argument names that collide only by case`() {
        execute(get(VALIDATED_ROUTE).queryParam("value", "accepted").queryParam("VALUE", "accepted"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))

        execute(
            query(VALIDATED_ROUTE).json(
                """{"arguments":{"value":"accepted","VALUE":"accepted"}}"""
            )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    @Test
    fun `GET and QUERY retain strict unknown client argument rejection`() {
        execute(get(VALIDATED_ROUTE).queryParam("unknown", "value"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))

        execute(query(VALIDATED_ROUTE).json("""{"arguments":{"unknown":"value"}}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    @Test
    fun `query validators participate in the real query pipeline`() {
        execute(get(VALIDATED_ROUTE).queryParam("value", "rejected"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].message").value("The query value was rejected."))

        execute(get(VALIDATED_ROUTE).queryParam("value", "accepted"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.value").value("accepted"))
    }

    private fun typedGet(
        identifier: UUID,
        queryId: UUID,
        time: String = LOCAL_TIME_NO_FRACTION,
        times: List<String> = listOf(LOCAL_TIME_NO_FRACTION, LOCAL_TIME_SEVEN_DIGITS)
    ): MockHttpServletRequestBuilder = get(TYPED_ROUTE)
        .queryParam("name", "alpha")
        .queryParam("count", "7")
        .queryParam("active", "TRUE")
        .queryParam("identifier", identifier.toString())
        .queryParam("state", "1")
        .queryParam("date", "2025-01-02")
        .queryParam("time", time)
        .queryParam("times", *times.toTypedArray())
        .queryParam("instant", "2025-01-02T03:04:05Z")
        .queryParam("ids", "11", "12")
        .queryParam("codes", "one", "two")
        .queryParam("queryId", queryId.toString())

    private fun typedQueryBody(
        identifier: UUID,
        queryId: UUID,
        time: String = LOCAL_TIME_NO_FRACTION,
        times: List<String> = listOf(LOCAL_TIME_NO_FRACTION, LOCAL_TIME_SEVEN_DIGITS)
    ): String {
        val timesJson = times.joinToString(",") { value -> "\"$value\"" }
        return """
            {
              "arguments": {
                "name": "alpha",
                "count": 7,
                "active": true,
                "identifier": "$identifier",
                "state": 1,
                "date": "2025-01-02",
                "time": "$time",
                "times": [$timesJson],
                "instant": "2025-01-02T03:04:05Z",
                "ids": [11, 12],
                "codes": ["one", "two"],
                "queryId": "$queryId",
                "dependency": "must-not-be-a-client-argument",
                "request": "must-not-be-a-client-argument",
                "context": "must-not-be-a-client-argument"
              },
              "paging": {"page": 2, "pageSize": 25},
              "sorting": {"field": "name", "direction": "desc"}
            }
        """.trimIndent()
    }

    private fun query(route: String): MockHttpServletRequestBuilder = request(HttpMethod.valueOf("QUERY"), route)

    private fun execute(requestBuilder: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun ResultActions.andExpectAcceptedLocalTimes(time: String): ResultActions =
        andExpect(status().isOk)
            .andExpect(jsonPath("$.data.time").value(time))
            .andExpect(jsonPath("$.data.times[0]").value(LOCAL_TIME_NO_FRACTION))
            .andExpect(jsonPath("$.data.times[1]").value(LOCAL_TIME_SEVEN_DIGITS))

    private fun ResultActions.andExpectSafeMalformedQuery(): ResultActions =
        andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(content().string(not(containsString("LocalTime"))))

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    class ClaimsPrincipal(private val principalName: String, val claims: Map<String, Any>) : java.security.Principal {
        override fun getName(): String = principalName
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun javaFixtureModule(): ArcArtifactModule = JavaFixtureArcArtifactModule()

        @Bean
        fun queryFixtureService(): JavaFixtureArcArtifactModule.QueryFixtureService =
            JavaFixtureArcArtifactModule.QueryFixtureService()

        @Bean
        fun queryValidator(): QueryValidator = object : QueryValidator {
            override val queryName = FullyQualifiedQueryName("io.cratis.arc.springboot.JavaQueryFixture.validated")

            override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> =
                if (request.arguments["value"] == "rejected") {
                    listOf(
                        ValidationResult(
                            ValidationResultSeverity.Error,
                            "The query value was rejected.",
                            listOf("value")
                        )
                    )
                } else {
                    emptyList()
                }
        }
    }

    private companion object {
        const val LOCAL_TIME_NO_FRACTION = "03:04:05"
        const val LOCAL_TIME_SEVEN_DIGITS = "03:04:05.1234567"
        const val LOCAL_TIME_EIGHT_DIGITS = "03:04:05.12345678"
        const val LOCAL_TIME_NINE_DIGITS = "03:04:05.123456789"
        const val TYPED_ROUTE = "/api/fixtures/typed-query"
        const val SECURED_ROUTE = "/api/fixtures/secured-query"
        const val NOT_READY_ROUTE = "/api/fixtures/not-ready-query"
        const val EXPLODING_ROUTE = "/api/fixtures/exploding-query"
        const val VALIDATED_ROUTE = "/api/fixtures/validated-query"
        const val COEXISTING_ROUTE = "/api/fixtures/java-fixture-command"
    }
}
