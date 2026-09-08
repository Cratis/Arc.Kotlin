// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.json.ArcJacksonModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Exercises runtime registration through real hosted commands, without changing KSP's hierarchy restrictions. */
@SpringBootTest(classes = [ArcMultilevelDerivedTypeHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcMultilevelDerivedTypeHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var invocations: AtomicInteger

    @BeforeEach
    fun resetInvocations() {
        invocations.set(0)
    }

    @Test
    fun `root command receives exact middle and returns its real handler response`() {
        execute(ROOT_ROUTE, """{"value":$MIDDLE_JSON}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response").value("Middle:root-value:middle-value"))
        assertEquals(1, invocations.get())
    }

    @Test
    fun `root command receives exact leaf including inherited fields`() {
        execute(ROOT_ROUTE, """{"value":$LEAF_JSON}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response").value("Leaf:leaf-root:leaf-middle:leaf-value"))
        assertEquals(1, invocations.get())
    }

    @Test
    fun `middle command receives exact leaf including inherited fields`() {
        execute(MIDDLE_ROUTE, """{"value":$LEAF_JSON}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response").value("Leaf:leaf-root:leaf-middle:leaf-value"))
        assertEquals(1, invocations.get())
    }

    @Test
    fun `root selected middle independently resolves its valid nested child`() {
        execute(ROOT_ROUTE, rootWithChild(LEAF_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.response").value("Middle:root-value:middle-value/Leaf:leaf-root:leaf-middle:leaf-value"))
        assertEquals(1, invocations.get())
    }

    @Test
    fun `direct middle self identifier is a safe malformed request without handler invocation`() {
        assertMalformed(execute(MIDDLE_ROUTE, """{"value":$MIDDLE_JSON}"""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"_derivedTypeId\":null}", "{\"_derivedTypeId\":42}",
        "{\"_derivedTypeId\":true}", "{\"_derivedTypeId\":{}}", "{\"_derivedTypeId\":[]}",
        "{\"_derivedTypeId\":\"unknown\"}", "{\"_derivedTypeId\":\"middle\"}",
        "{\"_derivedTypeId\":\"sibling\"}"])
    fun `invalid child identifiers produce safe malformed requests without handler invocation`(child: String) {
        val identifier = child.removeSurrounding("{", "}")
        val suffix = if (identifier.isEmpty()) "" else ",$identifier"
        val completeChild = """{"inherited":"child-root","middle":"child-middle"$suffix}"""
        assertMalformed(execute(ROOT_ROUTE, rootWithChild(completeChild)))
    }

    private fun assertMalformed(result: ResultActions) {
        val body = result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andReturn().response.contentAsString

        listOf("Json", "Jackson", "_derivedTypeId", "Unknown derived", "Missing textual", "io.cratis.arc",
            Root::class.java.name, Middle::class.java.name, Leaf::class.java.name).forEach { detail ->
            assertFalse(body.contains(detail), body)
        }
        assertEquals(0, invocations.get())
    }

    private fun execute(route: String, json: String): ResultActions {
        val initial = mockMvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(request().asyncStarted()).andReturn()
        initial.getAsyncResult(5_000)
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun rootWithChild(child: String): String =
        """{"value":{"_derivedTypeId":"middle","inherited":"root-value","middle":"middle-value","child":$child}}"""

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun multilevelInvocations(): AtomicInteger = AtomicInteger()

        @Bean
        fun multilevelModule(invocations: AtomicInteger): ArcArtifactModule = MultilevelModule(invocations)

        @Bean
        fun multilevelJacksonModule(): ArcJacksonModule {
            val registry = ConcurrentDerivedTypeRegistry().apply {
                register(Root::class.java, Middle::class.java)
                register(Root::class.java, Leaf::class.java)
                register(Middle::class.java, Leaf::class.java)
                register(OtherRoot::class.java, Sibling::class.java)
            }
            return ArcJacksonModule(registry)
        }
    }

    abstract class Root(val inherited: String)

    @DerivedType("middle")
    open class Middle(inherited: String, val middle: String, val child: Middle? = null) : Root(inherited)

    @DerivedType("leaf")
    class Leaf(inherited: String, middle: String, val leaf: String) : Middle(inherited, middle)

    abstract class OtherRoot

    @DerivedType("sibling")
    class Sibling : OtherRoot()

    data class RootCommand(val value: Root)
    data class MiddleCommand(val value: Middle)

    private class MultilevelModule(invocations: AtomicInteger) : ArcArtifactModule(
        listOf(RootHandler(invocations), MiddleHandler(invocations)),
        emptyList()
    )

    private class RootHandler(private val invocations: AtomicInteger) : CommandHandler {
        override val commandType: Class<*> = RootCommand::class.java
        override val metadata = descriptor("RootCommand", commandType)

        override suspend fun invoke(context: CommandContext): Any {
            invocations.incrementAndGet()
            return describe((context.command as RootCommand).value)
        }
    }

    private class MiddleHandler(private val invocations: AtomicInteger) : CommandHandler {
        override val commandType: Class<*> = MiddleCommand::class.java
        override val metadata = descriptor("MiddleCommand", commandType)

        override suspend fun invoke(context: CommandContext): Any {
            invocations.incrementAndGet()
            return describe((context.command as MiddleCommand).value)
        }
    }

    private companion object {
        const val ROOT_ROUTE = "/api/multilevel/root-command"
        const val MIDDLE_ROUTE = "/api/multilevel/middle-command"
        const val MIDDLE_JSON =
            """{"_derivedTypeId":"middle","inherited":"root-value","middle":"middle-value"}"""
        const val LEAF_JSON =
            """{"_derivedTypeId":"leaf","inherited":"leaf-root","middle":"leaf-middle","leaf":"leaf-value"}"""

        fun descriptor(name: String, type: Class<*>) = CommandDescriptor(
            name,
            type.name,
            location = listOf("multilevel"),
            authorization = AuthorizationMetadata(true, null, emptyList(), emptyList()),
            responseTypeName = String::class.java.name
        )

        fun describe(value: Root): String {
            val middle = value as Middle
            val fields = "${value.javaClass.simpleName}:${value.inherited}:${middle.middle}"
            val response = if (value is Leaf) "$fields:${value.leaf}" else fields
            return middle.child?.let { "$response/${describe(it)}" } ?: response
        }
    }
}
