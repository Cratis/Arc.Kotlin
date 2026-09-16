// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.BlockingQueryRendererFor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRendererResult
import io.cratis.arc.results.PagingInfo
import org.hamcrest.Matchers.hasSize
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ArcQueryRendererOwnershipHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcQueryRendererOwnershipHostingTests {
    @Autowired lateinit var mockMvc: MockMvc

    @ParameterizedTest
    @ValueSource(strings = ["GET", "QUERY"])
    fun `application filtering is not undone by implicit rendering`(method: String) {
        execute(method, "filtered").andExpect(jsonPath("$.data", hasSize<Any>(0)))
            .andExpect(jsonPath("$.paging.totalItems").value(0))
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET", "QUERY"])
    fun `application provider owns its page and original source is not enumerated`(method: String) {
        execute(method, "provider").andExpect(jsonPath("$.data", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data[0].id").value(3))
            .andExpect(jsonPath("$.data[1].id").value(4))
            .andExpect(jsonPath("$.paging.page").value(1))
            .andExpect(jsonPath("$.paging.size").value(2))
            .andExpect(jsonPath("$.paging.totalItems").value(40))
            .andExpect(jsonPath("$.paging.totalPages").value(20))
    }

    private fun execute(method: String, query: String): ResultActions {
        val path = "/api/renderer-ownership/$query"
        val builder = if (method == "GET") get(path).queryParam("page", "1").queryParam("pageSize", "2") else
            request(HttpMethod.valueOf("QUERY"), path).contentType(MediaType.APPLICATION_JSON)
                .content("""{"arguments":{},"paging":{"page":1,"pageSize":2}}""")
        val initial = mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk).andExpect(jsonPath("$.isSuccess").value(true))
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun ownershipModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), listOf(
            Performer("filtered", FilterRows()), Performer("provider", ProviderRows())
        )) {}

        @Bean
        fun filteringRenderer(): BlockingQueryRendererFor<FilterRows> = object : BlockingQueryRendererFor<FilterRows> {
            override fun queryType(): Class<FilterRows> = FilterRows::class.java
            override fun renderBlocking(query: FilterRows, current: QueryRendererResult, context: QueryContext): QueryRendererResult =
                QueryRendererResult(emptyList<Row>(), current.paging)
        }

        @Bean
        fun providerRenderer(): BlockingQueryRendererFor<ProviderRows> = object : BlockingQueryRendererFor<ProviderRows> {
            override fun queryType(): Class<ProviderRows> = ProviderRows::class.java
            override fun order(): Int = 100
            override fun renderBlocking(query: ProviderRows, current: QueryRendererResult, context: QueryContext): QueryRendererResult =
                QueryRendererResult(listOf(Row(3), Row(4)), PagingInfo(1, 2, 40))
        }
    }

    private class Performer(name: String, private val source: Any) : QueryPerformer {
        override val descriptor = QueryDescriptor(name, "RendererOwnership", Row::class.java.name,
            authorization = AuthorizationMetadata(allowAnonymous = true), explicitPath = "/api/renderer-ownership/$name", isEnumerable = true)
        override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
        override suspend fun perform(context: QueryContext): Any = source
    }

    data class Row(val id: Int)
    class FilterRows : Iterable<Row> {
        override fun iterator(): Iterator<Row> = listOf(Row(0), Row(1), Row(2), Row(3)).iterator()
    }
    class ProviderRows : Iterable<Row> {
        override fun iterator(): Iterator<Row> = error("Provider source must not be enumerated by implicit fallback")
    }
}
