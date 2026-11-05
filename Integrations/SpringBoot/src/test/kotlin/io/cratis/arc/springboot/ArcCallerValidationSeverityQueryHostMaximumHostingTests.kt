// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    classes = [ArcCallerValidationSeverityQueryHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["cratis.arc.command.maximum-caller-allowed-severity=Error"],
)
internal class ArcCallerValidationSeverityQueryHostMaximumHostingTests {
    @LocalServerPort
    var port: Int = 0

    @Test
    fun `queries and observable queries honor an explicit host maximum of Error`() {
        listOf(false, true).forEach { observable ->
            listOf("3", "Error").forEach { header ->
                assertEquals(200, querySeverityRequest(port, observable, "plain", "Error", header).statusCode())
            }
        }
    }

    @Test
    fun `query artifact maximum and warnings as errors floor constrain the host maximum`() {
        listOf(false, true).forEach { observable ->
            querySeverityRequest(port, observable, "stricter", "Warning", "Error").expectQuerySeverity(2)
            querySeverityRequest(port, observable, "warnings-as-errors", "Warning", "Error").expectQuerySeverity(2)
        }
    }
}
