// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.polymorphism.DerivedType
import io.cratis.arc.polymorphism.DerivedTypeRegistrar
import io.cratis.arc.polymorphism.DerivedTypeRegistration
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * A hosted application must be able to read a `@DerivedType` value, not only write one.
 *
 * The registry behind Arc's Jackson module is populated from the generated artifact modules, so the identifier a
 * command body carries resolves to the concrete type. An identifier nothing registered stays a refused request.
 */
@SpringBootTest(classes = [ArcDerivedTypeHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcDerivedTypeHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a command body carrying a derived value resolves the concrete type`() {
        execute(post(COMMAND_ROUTE).json("""{"shape":{"radius":2.0,"_derivedTypeId":"circle"}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.typeName").value(HostedCircle::class.java.name))
    }

    @Test
    fun `a derived value in the response is written with its identifier`() {
        execute(post(COMMAND_ROUTE).json("""{"shape":{"side":3.0,"_derivedTypeId":"square"}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.response.shape._derivedTypeId").value("square"))
            .andExpect(jsonPath("$.response.shape.side").value(3.0))
    }

    @Test
    fun `a hierarchy contributed by a registrar resolves the same way`() {
        execute(post(COMMAND_ROUTE).json("""{"shape":{"corners":5,"_derivedTypeId":"polygon"}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.response.typeName").value(HostedPolygon::class.java.name))
    }

    @Test
    fun `an identifier nothing registered is a refused request rather than a null value`() {
        execute(post(COMMAND_ROUTE).json("""{"shape":{"radius":2.0,"_derivedTypeId":"triangle"}}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
    }

    private fun execute(requestBuilder: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun derivedTypeFixtureModule(): ArcArtifactModule = DerivedTypeFixtureArcArtifactModule()

        @Bean
        fun derivedTypeFixtureRegistrar(): DerivedTypeRegistrar = DerivedTypeRegistrar { registry ->
            registry.register(HostedShape::class.java, HostedPolygon::class.java)
        }
    }

    private companion object {
        const val COMMAND_ROUTE = "/api/polymorphism/derived-type-fixture-command"
    }
}

/** Base contract for the hosted polymorphic fixture. */
internal interface HostedShape

/** First concrete derivative of [HostedShape]. */
@DerivedType("circle")
internal data class HostedCircle(val radius: Double) : HostedShape

/** Second concrete derivative of [HostedShape]. */
@DerivedType("square")
internal data class HostedSquare(val side: Double) : HostedShape

/** Derivative that stands in for one arriving from a dependency binary code generation never saw. */
@DerivedType("polygon")
internal data class HostedPolygon(val corners: Int) : HostedShape

internal data class DerivedTypeFixtureCommand(val shape: HostedShape)

internal data class DerivedTypeFixtureResponse(val typeName: String, val shape: HostedShape)

internal class DerivedTypeFixtureArcArtifactModule : ArcArtifactModule(
    commandHandlers = listOf(DerivedTypeFixtureCommandHandler()),
    queryPerformers = emptyList(),
    derivedTypes = listOf(
        DerivedTypeRegistration(HostedShape::class.java, HostedCircle::class.java),
        DerivedTypeRegistration(HostedShape::class.java, HostedSquare::class.java)
    )
)

internal class DerivedTypeFixtureCommandHandler : CommandHandler {
    override val commandType: Class<*> = DerivedTypeFixtureCommand::class.java

    override val metadata: CommandDescriptor = CommandDescriptor(
        "DerivedTypeFixtureCommand",
        DerivedTypeFixtureCommand::class.java.name,
        emptyList(),
        RouteOptions("/api/polymorphism/derived-type-fixture-command"),
        listOf("polymorphism"),
        AuthorizationMetadata(true, null, emptyList(), emptyList()),
        "/api/polymorphism/derived-type-fixture-command",
        false
    )

    override suspend fun invoke(context: CommandContext): Any? {
        val shape = (context.command as DerivedTypeFixtureCommand).shape
        return DerivedTypeFixtureResponse(shape.javaClass.name, shape)
    }
}
