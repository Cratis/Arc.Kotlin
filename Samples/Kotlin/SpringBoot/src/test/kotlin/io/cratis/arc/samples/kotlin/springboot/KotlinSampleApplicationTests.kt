// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.springboot.ArcArtifactModules
import java.util.ServiceLoader
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
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

/** End-to-end verification of generated Arc hosting for the Kotlin sample. */
@SpringBootTest
@AutoConfigureMockMvc
public class KotlinSampleApplicationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var repository: TaskRepository

    @Autowired
    private lateinit var artifactModules: ArcArtifactModules

    @Autowired
    private lateinit var taskCreationResponseValueHandler: TaskCreationResponseValueHandler

    @BeforeEach
    public fun resetSampleState() {
        repository.clear()
        taskCreationResponseValueHandler.clear()
    }

    @Test
    public fun `generated module is service-loadable and command returns a typed response`() {
        val loaded = ServiceLoader.load(ArcArtifactModule::class.java).toList()
        assertEquals(listOf("KotlinSpringBootSampleArcArtifactModule"), loaded.map { it.javaClass.simpleName })
        assertEquals(loaded.map(Any::javaClass), artifactModules.modules.map(Any::javaClass))

        val command = execute(post(CREATE_ROUTE).json("""{"title":"Write Kotlin sample"}"""))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.id").isString)
            .andExpect(jsonPath("$.response.title").value("Write Kotlin sample"))
            .andExpect(jsonPath("$.validationResults", hasSize<Any>(0)))
            .andExpect(jsonPath("$.exceptionMessages", hasSize<Any>(0)))
            .andReturn()
        val taskId = objectMapper.readTree(command.response.contentAsString).path("response").path("id").asText()
        assertTrue(taskCreationResponseValueHandler.hasHandled(taskId))
    }

    @Test
    public fun `complete command consumes provided task and returns the updated typed response`() {
        val created = repository.create("Exercise provide flow")

        execute(post(COMPLETE_ROUTE).json("""{"taskId":"${created.id}"}"""))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.id").value(created.id))
            .andExpect(jsonPath("$.response.title").value("Exercise provide flow"))
            .andExpect(jsonPath("$.response.completed").value(true))
            .andExpect(jsonPath("$.validationResults", hasSize<Any>(0)))
            .andExpect(jsonPath("$.exceptionMessages", hasSize<Any>(0)))

        assertEquals(created.copy(completed = true), repository.byId(created.id))
    }

    @Test
    public fun `complete command returns provide validation for a missing task`() {
        execute(post(COMPLETE_ROUTE).json("""{"taskId":"missing-task"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("taskId"))
            .andExpect(jsonPath("$.validationResults[0].message").value("The task does not exist."))
            .andExpect(jsonPath("$.exceptionMessages", hasSize<Any>(0)))

        assertTrue(repository.all().isEmpty())
    }

    @Test
    public fun `batch command returns typed responses and handles its bounded side effect`() {
        val command = execute(post(BATCH_CREATE_ROUTE).json("""{"titles":["First batch task","Second batch task"]}"""))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response", hasSize<Any>(2)))
            .andExpect(jsonPath("$.response[0].title").value("First batch task"))
            .andExpect(jsonPath("$.response[1].title").value("Second batch task"))
            .andExpect(jsonPath("$.validationResults", hasSize<Any>(0)))
            .andExpect(jsonPath("$.exceptionMessages", hasSize<Any>(0)))
            .andReturn()

        val taskIds = objectMapper.readTree(command.response.contentAsString).path("response")
            .map { response -> response.path("id").asText() }
        assertTrue(taskCreationResponseValueHandler.hasHandledBatch(taskIds))
        assertEquals(listOf("First batch task", "Second batch task"), repository.all().map(TaskView::title))
    }

    @Test
    public fun `batch command rejects a blank title before repository or response handling side effects`() {
        execute(post(BATCH_CREATE_ROUTE).json("""{"titles":["Valid first title",""]}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("titles"))
            .andExpect(jsonPath("$.validationResults[0].message").value("Every task in a batch requires a title."))

        assertTrue(repository.all().isEmpty())
        assertTrue(taskCreationResponseValueHandler.hasHandledBatch(emptyList()))
    }

    @Test
    public fun `batch command rejects an overlong title before repository or response handling side effects`() {
        val overlongTitle = "x".repeat(TaskTitleRule.MAXIMUM_LENGTH + 1)
        execute(post(BATCH_CREATE_ROUTE).json("""{"titles":["Valid first title","$overlongTitle"]}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("titles"))
            .andExpect(
                jsonPath("$.validationResults[0].message")
                    .value("A task title cannot exceed ${TaskTitleRule.MAXIMUM_LENGTH} characters.")
            )

        assertTrue(repository.all().isEmpty())
        assertTrue(taskCreationResponseValueHandler.hasHandledBatch(emptyList()))
    }

    @Test
    public fun `validate rejects invalid commands without changing state`() {
        execute(post("$CREATE_ROUTE/validate").json("""{"title":""}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("title"))
            .andExpect(jsonPath("$.validationResults[0].message").value("A task title is required."))

        execute(post("$BATCH_CREATE_ROUTE/validate").json("""{"titles":["One","Two","Three","Four"]}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("titles"))
            .andExpect(jsonPath("$.validationResults[0].message").value("A task batch cannot contain more than 3 titles."))
        assertTrue(repository.all().isEmpty())
    }

    @Test
    public fun `GET by id and RFC QUERY list return typed query envelopes`() {
        val command = execute(post(CREATE_ROUTE).json("""{"title":"Read generated query"}"""))
            .andExpect(status().isOk)
            .andReturn()
        val id = objectMapper.readTree(command.response.contentAsString).path("response").path("id").asText()

        execute(get(BY_ID_ROUTE).queryParam("id", id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.title").value("Read generated query"))
            .andExpect(jsonPath("$.data.completed").value(false))
            .andExpect(jsonPath("$.paging").exists())

        execute(query(LIST_ROUTE).json("""{"arguments":{}}"""))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data", hasSize<Any>(1)))
            .andExpect(jsonPath("$.data[0].id").value(id))
    }

    @Test
    public fun `standalone identity endpoint returns the configured typed details`() {
        execute(get(IDENTITY_ROUTE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value("arc-kotlin-runtime-gate"))
            .andExpect(jsonPath("$.name").value("Arc Kotlin Runtime Gate"))
            .andExpect(jsonPath("$.isAuthenticated").value(true))
            .andExpect(jsonPath("$.isAuthorized").value(true))
            .andExpect(jsonPath("$.details.source").value("Arc.Kotlin sample"))
    }

    @Test
    public fun `map command and GET query use recursive JSON objects and omit null maps`() {
        val payload =
            """{"strings":{"language":"kotlin"},"numbers":{"values":[1,2]},"nested":{"flags":{"ready":true}},"optional":null}"""

        execute(post(ECHO_MAPS_ROUTE).json(payload))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.strings.language").value("kotlin"))
            .andExpect(jsonPath("$.response.numbers.values[1]").value(2))
            .andExpect(jsonPath("$.response.nested.flags.ready").value(true))
            .andExpect(jsonPath("$.response.optional").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("_entries"))))

        execute(get(MAPS_QUERY_ROUTE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data.strings.source").value("query"))
            .andExpect(jsonPath("$.data.numbers.values[0]").value(1))
            .andExpect(jsonPath("$.data.nested.flags.ready").value(true))
            .andExpect(jsonPath("$.data.optional").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("_entries"))))
    }

    @Test
    public fun `calendar command binds scalar JSON strings and returns its typed model`() {
        execute(
            post(ECHO_CALENDAR_ROUTE).json(
                """{"identifier":"$CALENDAR_IDENTIFIER","date":"$CALENDAR_DATE","time":"$CALENDAR_TIME"}"""
            )
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.identifier").value(CALENDAR_IDENTIFIER))
            .andExpect(jsonPath("$.response.date").value(CALENDAR_DATE))
            .andExpect(jsonPath("$.response.time").value(CALENDAR_TIME))
    }

    @Test
    public fun `calendar GET query binds direct parameters and returns its typed model`() {
        execute(
            get(CALENDAR_QUERY_ROUTE)
                .queryParam("identifier", CALENDAR_IDENTIFIER)
                .queryParam("date", CALENDAR_DATE)
                .queryParam("time", CALENDAR_TIME)
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data.identifier").value(CALENDAR_IDENTIFIER))
            .andExpect(jsonPath("$.data.date").value(CALENDAR_DATE))
            .andExpect(jsonPath("$.data.time").value(CALENDAR_TIME))
    }

    @Test
    public fun `GET and RFC QUERY preserve Kotlin default omission and supplied values`() {
        val getCorrelationId = "3db81326-e264-45ff-a678-92c41c98078c"
        execute(get(CALENDAR_DEFAULT_GET_ROUTE).header("X-Correlation-ID", getCorrelationId))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-ID", getCorrelationId))
            .andExpect(jsonPath("$.correlationId").value(getCorrelationId))
            .andExpect(jsonPath("$.data.date").value("2026-01-01"))

        execute(get(CALENDAR_DEFAULT_GET_ROUTE).queryParam("year", "2030"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.date").value("2030-01-01"))

        execute(get(CALENDAR_DEFAULT_GET_ROUTE).queryParam("year", "not-a-year"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))

        val queryCorrelationId = "cd095743-82f0-4bda-aa21-dac87e2fdfc9"
        execute(
            query(CALENDAR_DEFAULT_QUERY_ROUTE)
                .header("X-Correlation-ID", queryCorrelationId)
                .json("""{"arguments":{}}""")
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Correlation-ID", queryCorrelationId))
            .andExpect(jsonPath("$.correlationId").value(queryCorrelationId))
            .andExpect(jsonPath("$.data.date").value("2027-01-01"))

        execute(query(CALENDAR_DEFAULT_QUERY_ROUTE).json("""{"arguments":{"year":2031}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.date").value("2031-01-01"))

        execute(query(CALENDAR_DEFAULT_QUERY_ROUTE).json("""{"arguments":{"year":null}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.date").value("2000-01-01"))

        execute(query(CALENDAR_DEFAULT_QUERY_ROUTE).json("""{"arguments":{"year":"not-a-year"}}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    @Test
    public fun `calendar precision query preserves seven fractional digits on the raw JVM wire`() {
        execute(get(CALENDAR_PRECISION_ROUTE))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data.time").value(CALENDAR_PRECISION_TIME))
            .andExpect(content().string(containsString("\"time\":\"$CALENDAR_PRECISION_TIME\"")))
    }

    @Test
    public fun `malformed command and query bodies produce safe validation envelopes`() {
        execute(post(CREATE_ROUTE).json("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(jsonPath("$.exceptionMessages", hasSize<Any>(0)))

        execute(query(LIST_ROUTE).json("{"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(jsonPath("$.exceptionMessages", hasSize<Any>(0)))
    }

    private fun execute(requestBuilder: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun query(route: String): MockHttpServletRequestBuilder = request(HttpMethod.valueOf("QUERY"), route)

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    private companion object {
        const val CREATE_ROUTE = "/api/create-task"
        const val COMPLETE_ROUTE = "/api/complete-task"
        const val BATCH_CREATE_ROUTE = "/api/create-task-batch"
        const val BY_ID_ROUTE = "/api/tasks/by-id"
        const val CALENDAR_DATE = "2026-01-01"
        const val CALENDAR_IDENTIFIER = "11111111-1111-1111-1111-111111111111"
        const val CALENDAR_DEFAULT_GET_ROUTE = "/api/calendar-default-get"
        const val CALENDAR_DEFAULT_QUERY_ROUTE = "/api/calendar-default-query"
        const val CALENDAR_PRECISION_ROUTE = "/api/calendar-precision"
        const val CALENDAR_PRECISION_TIME = "08:09:10.1235567"
        const val CALENDAR_QUERY_ROUTE = "/api/calendar-echo"
        const val CALENDAR_TIME = "14:30:45.123"
        const val ECHO_CALENDAR_ROUTE = "/api/echo-calendar"
        const val ECHO_MAPS_ROUTE = "/api/echo-maps"
        const val IDENTITY_ROUTE = "/.cratis/me"
        const val LIST_ROUTE = "/api/tasks"
        const val MAPS_QUERY_ROUTE = "/api/maps"
    }
}
