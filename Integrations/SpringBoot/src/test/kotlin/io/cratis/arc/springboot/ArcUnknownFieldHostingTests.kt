// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Characterizes unknown-field handling on the hosted Arc surfaces against the host-agnostic mapper.
 *
 * Neither [ArcObjectMapper] nor `arcJacksonCustomizer` touches `FAIL_ON_UNKNOWN_PROPERTIES`, so a hosted command
 * body inherits Spring Boot's relaxed default while the standalone mapper inherits Jackson's strict default. The
 * QUERY envelope is unaffected by either, because `ArcQueryHttpRequestHandler` validates its own field set.
 */
@SpringBootTest(classes = [ArcUnknownFieldHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcUnknownFieldHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `hosted mapper relaxes the unknown-field default that the standalone mapper keeps`() {
        assertFalse(objectMapper.deserializationConfig.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
        assertTrue(
            ArcObjectMapper.create().deserializationConfig
                .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        )
    }

    @Test
    fun `hosted command body ignores an unknown field`() {
        execute(post(COMMAND_ROUTE).json("""{"value":"hello","unexpected":"extra"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.message").value("handled:hello"))
    }

    @Test
    fun `QUERY envelope rejects an unknown envelope field`() {
        execute(query(VALIDATED_ROUTE).json("""{"arguments":{"value":"accepted"}}"""))
            .andExpect(status().isOk)

        execute(query(VALIDATED_ROUTE).json("""{"arguments":{"value":"accepted"},"unexpected":"extra"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    private fun query(route: String): MockHttpServletRequestBuilder = request(HttpMethod.valueOf("QUERY"), route)

    private fun execute(requestBuilder: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun javaFixtureModule(): ArcArtifactModule = JavaFixtureArcArtifactModule()
    }

    private companion object {
        const val COMMAND_ROUTE = "/api/fixtures/java-fixture-command"
        const val VALIDATED_ROUTE = "/api/fixtures/validated-query"
    }
}
