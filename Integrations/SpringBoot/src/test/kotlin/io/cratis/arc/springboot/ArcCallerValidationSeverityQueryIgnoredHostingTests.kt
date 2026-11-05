// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    classes = [ArcCallerValidationSeverityQueryHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["cratis.arc.command.ignore-caller-allowed-severity=true"],
)
internal class ArcCallerValidationSeverityQueryIgnoredHostingTests {
    @LocalServerPort
    var port: Int = 0

    @Test
    fun `queries and observable queries ignore caller severity even with an artifact opt in`() {
        listOf(false, true).forEach { observable ->
            querySeverityRequest(port, observable, "opted-in", "Error", "Error").expectQuerySeverity(3)
            querySeverityRequest(port, observable, "warnings-as-errors", "Warning", "Warning").expectQuerySeverity(2)
        }
    }
}
