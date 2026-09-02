// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.openapi.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.swagger.v3.oas.models.OpenAPI
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest(classes = [ArcOpenApiEndpointTests.Application::class])
@AutoConfigureMockMvc
internal class ArcOpenApiEndpointTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `both routes serve the same cached OpenAPI document`() {
        val conventional = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("\"openapi\" : \"3.1.0\"")))
            .andReturn().response.contentAsByteArray
        val arc = mockMvc.perform(get("/.cratis/openapi.json"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
        assertArrayEquals(conventional, arc)
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}
    }
}

@SpringBootTest(classes = [ArcOpenApiRouteConflictTests.Application::class])
@AutoConfigureMockMvc
internal class ArcOpenApiRouteConflictTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `application route wins while the unclaimed Arc route remains available`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(content().string("application-owned"))
        mockMvc.perform(get("/.cratis/openapi.json"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("\"openapi\" : \"3.1.0\"")))
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}

        @Bean
        fun applicationDocsController(): ApplicationDocsController = ApplicationDocsController()
    }

    @RestController
    class ApplicationDocsController {
        @GetMapping("/v3/api-docs")
        fun document(): String = "application-owned"
    }
}

@SpringBootTest(classes = [ArcOpenApiBeanBackoffTests.Application::class])
@AutoConfigureMockMvc
internal class ArcOpenApiBeanBackoffTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `application OpenAPI bean prevents Arc document and routes`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isNotFound)
            .andExpect(content().string(not(containsString("3.1.0"))))
        mockMvc.perform(get("/.cratis/openapi.json")).andExpect(status().isNotFound)
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}

        @Bean
        fun applicationOpenApi(): OpenAPI = OpenAPI().openapi("3.1.1")
    }
}
