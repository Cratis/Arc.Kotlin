// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPage
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPipeline
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ArcQueryPagingSortingHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcQueryPagingSortingHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var pipeline: QueryPipeline

    @ParameterizedTest
    @ValueSource(strings = ["GET", "QUERY"])
    fun `host sorts list data before paging despite false descriptor capabilities`(method: String) {
        execute(method, "list")
            .andExpect(jsonPath("$.data", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data[0].name").value("b"))
            .andExpect(jsonPath("$.data[1].name").value("a"))
            .andExpectPaging(1, 2, 4, 2)
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET", "QUERY"])
    fun `host preserves provider page data order and total without repaging`(method: String) {
        execute(method, "page")
            .andExpect(jsonPath("$.data", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data[0].name").value("a"))
            .andExpect(jsonPath("$.data[1].name").value("c"))
            .andExpectPaging(3, 2, 40, 20)
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET", "QUERY"])
    fun `host leaves original array data unsorted and unpaged`(method: String) {
        execute(method, "array")
            .andExpect(jsonPath("$.data", hasSize<Any>(4)))
            .andExpect(jsonPath("$.data[0].name").value("a"))
            .andExpect(jsonPath("$.data[1].name").value("c"))
            .andExpect(jsonPath("$.data[2].name").value("b"))
            .andExpect(jsonPath("$.data[3].name").value("d"))
            .andExpectPaging(0, 0, 0, 0)
    }

    private fun execute(method: String, shape: String): ResultActions {
        assertEquals(DefaultQueryPipeline::class.java, pipeline.javaClass)
        val route = "/api/paging-execution/$shape"
        val builder = if (method == "GET") {
            get(route)
                .queryParam("page", "1")
                .queryParam("pageSize", "2")
                .queryParam("sortBy", "name")
                .queryParam("sortDirection", "descending")
        } else {
            request(HttpMethod.valueOf("QUERY"), route)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"arguments":{},"paging":{"page":1,"pageSize":2},"sorting":{"field":"name","direction":"descending"}}"""
                )
        }
        val initial = mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
        val result = mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
        if (method == "QUERY") result.andExpect(header().string("Cache-Control", "no-store"))
        return result
    }

    private fun ResultActions.andExpectPaging(page: Int, size: Int, total: Int, totalPages: Int): ResultActions =
        andExpect(jsonPath("$.paging.page").value(page))
            .andExpect(jsonPath("$.paging.size").value(size))
            .andExpect(jsonPath("$.paging.totalItems").value(total))
            .andExpect(jsonPath("$.paging.totalPages").value(totalPages))
            .andExpect(jsonPath("$.paging.pageSize").doesNotExist())

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun pagingExecutionModule(): ArcArtifactModule {
            val rows = listOf(Row("a"), Row("c"), Row("b"), Row("d"))
            // Manual registration isolates host execution; this fixture makes no KSP-generation claim.
            return object : ArcArtifactModule(
                emptyList(),
                listOf(
                    Performer("list", rows, SequenceKind.LIST),
                    Performer("page", QueryPage(listOf(Row("a"), Row("c")), 3, 2, 40), SequenceKind.LIST),
                    Performer("array", rows.toTypedArray(), SequenceKind.ARRAY)
                )
            ) {}
        }
    }

    private class Performer(name: String, private val value: Any, kind: SequenceKind) : QueryPerformer {
        override val descriptor = QueryDescriptor(
            name,
            "Tests.HostPagingRows",
            TypeShapeDescriptor.sequence(kind, TypeShapeDescriptor.value(Row::class.java.name)),
            authorization = AuthorizationMetadata(allowAnonymous = true),
            explicitPath = "/api/paging-execution/$name",
            supportsPaging = false,
            supportsSorting = false
        )
        override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)

        // The fixture declares no model parameters and does not inject or consume request/context state.
        override suspend fun perform(context: QueryContext): Any = value
    }

    data class Row(val name: String)
}
