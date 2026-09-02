// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [ArcQueryHostingTests.Application::class],
    properties = ["cratis.arc.endpoints.enable-query-http-method=false"]
)
@AutoConfigureMockMvc
internal class ArcQueryHttpMethodDisabledTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `disabled QUERY method returns method not allowed for an available GET query`() {
        mockMvc.perform(request(HttpMethod.valueOf("QUERY"), "/api/fixtures/typed-query"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", "GET"))
    }
}
