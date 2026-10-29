// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [ArcCallerValidationSeverityHostingTests.Application::class],
    properties = ["cratis.arc.command.maximum-caller-allowed-severity=Error"]
)
@AutoConfigureMockMvc
internal class ArcCallerValidationSeverityHostMaximumHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `host maximum permits callers to relax errors without a command annotation`() {
        mockMvc.severityRequest("plain", "error", "3").andExpect(status().isOk)
    }

    @Test
    fun `host maximum cannot relax the warnings as errors floor`() {
        listOf("Warning", "1", "2", "3").forEach { header ->
            listOf("", "/validate").forEach { suffix ->
                mockMvc.severityRequest("warnings-as-errors", "warning", header, suffix).expectSeverityError(2)
            }
        }
    }

    @Test
    fun `command maximum replaces a more permissive host maximum`() {
        mockMvc.severityRequest("stricter", "warning", "2").expectSeverityError(2)
    }
}
