// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ArcQueryPropertyAccessHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcQueryPropertyAccessHostingTests {
    @Autowired lateinit var mockMvc: MockMvc

    @ParameterizedTest
    @ValueSource(strings = ["GET", "QUERY"])
    fun `private sort fields cannot disclose hidden state through public result order`(method: String) {
        val unsorted = mockMvc.perform(get("/api/access-rows")).andExpect(request().asyncStarted()).andReturn()
        val visible = mockMvc.perform(asyncDispatch(unsorted)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].label").value("first"))
            .andExpect(jsonPath("$.data[1].label").value("second"))
            .andReturn().response.contentAsString
        assertFalse("privateRank" in visible, visible)

        val sortedRequest = if (method == "GET") get("/api/access-rows").queryParam("sortBy", "privateRank") else
            request(HttpMethod.valueOf("QUERY"), "/api/access-rows").contentType(MediaType.APPLICATION_JSON)
                .content("""{"arguments":{},"sorting":{"field":"privateRank","direction":"ascending"}}""")
        val initial = mockMvc.perform(sortedRequest).andExpect(request().asyncStarted()).andReturn()
        val rejected = mockMvc.perform(asyncDispatch(initial)).andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andReturn().response.contentAsString
        // Follow the existing unknown-sort-property failure/redaction contract; do not expose class/field details.
        assertFalse("privateRank" in rejected, rejected)
        assertFalse("ConfidentialRow" in rejected, rejected)
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun queryAccessModule(): ArcArtifactModule = object : ArcArtifactModule(commandHandlers = emptyList(), queryPerformers = listOf(object : QueryPerformer {
            override val descriptor = QueryDescriptor("rows", "AccessRows", ConfidentialRow::class.java.name,
                authorization = AuthorizationMetadata(allowAnonymous = true), explicitPath = "/api/access-rows", isEnumerable = true)
            override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
            override suspend fun perform(context: QueryContext): Any = listOf(
                ConfidentialRow("first", 99), ConfidentialRow("second", 1)
            )
        })) {}
    }

    class ConfidentialRow(val label: String, rank: Int) {
        private val privateRank = rank
    }
}
