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
    properties = ["cratis.arc.command.ignore-caller-allowed-severity=true"]
)
@AutoConfigureMockMvc
internal class ArcCallerValidationSeverityIgnoredHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `ignore overrides the command opt in`() {
        mockMvc.severityRequest("opted-in", "error", "3").expectSeverityError(3)
    }

    @Test
    fun `ignored header cannot request stricter validation`() {
        mockMvc.severityRequest("plain", "information", "0").andExpect(status().isOk)
    }

    @Test
    fun `ignored header cannot relax warnings as errors default`() {
        mockMvc.severityRequest("warnings-as-errors", "warning", "2").expectSeverityError(2)
    }
}
