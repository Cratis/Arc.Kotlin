// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [ArcTenancyRequiredHostingTests.Application::class],
    properties = [
        "cratis.arc.tenancy.resolvers=query",
        "cratis.arc.tenancy.required=true"
    ]
)
@AutoConfigureMockMvc
internal class ArcTenancyRequiredHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `required unresolved tenant fails closed before command execution`() {
        JavaFixtureArcArtifactModule.capturedContext = null
        val initial = mockMvc.perform(
            post("/api/fixtures/java-fixture-command")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"value":"unresolved"}""")
        )
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
        assertNull(JavaFixtureArcArtifactModule.capturedContext)
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun javaFixtureModule(): ArcArtifactModule = JavaFixtureArcArtifactModule()
    }
}
