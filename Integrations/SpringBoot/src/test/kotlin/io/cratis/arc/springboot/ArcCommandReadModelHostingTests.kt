// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerArgumentResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.queries.BlockingReadModelForCommandResolver
import io.cratis.arc.queries.ReadModelForCommandOwnership
import java.util.UUID
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [ArcCommandReadModelHostingTests.Application::class],
    properties = ["cratis.arc.correlation-header=X-Arc-Request-ID"]
)
@AutoConfigureMockMvc
internal class ArcCommandReadModelHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `owned read model absence is HTTP 400 for required parameters and a value for nullable and Optional parameters`() {
        listOf(REQUIRED_ROUTE, MISSING_KEY_ROUTE).forEach { route ->
            execute(post(route).json("""{"id":"missing"}"""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.validationResults.length()").value(1))
                .andExpect(jsonPath("$.validationResults[0].reason").value("dependencyUnavailable"))
                .andExpect(jsonPath("$.exceptionMessages.length()").value(0))
        }

        execute(post(NULLABLE_ROUTE).json("""{"id":"missing"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.response").value("kotlin:none"))

        execute(post(OPTIONAL_ROUTE).json("""{"id":"missing"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.response").value("java:none"))
    }

    @Test
    fun `configured correlation header is captured and echoed for hosted commands`() {
        val correlationId = UUID.fromString("0e936c49-23c4-45b4-bdd1-333bc62f9d35")
        execute(
            post(NULLABLE_ROUTE)
                .header("X-Arc-Request-ID", correlationId.toString())
                .json("""{"id":"missing"}""")
        )
            .andExpect(status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string(
                "X-Arc-Request-ID",
                correlationId.toString()
            ))
            .andExpect(jsonPath("$.correlationId").value(correlationId.toString()))
    }

    private fun execute(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.json(value: String) =
        contentType(MediaType.APPLICATION_JSON).content(value)

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun module(): ArcArtifactModule = HostedReadModelModule()

        @Bean
        fun missingReadModelResolver(): BlockingReadModelForCommandResolver = object : BlockingReadModelForCommandResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(HostedReadModel::class.java)
            override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
            override fun resolveBlocking(
                readModelType: Class<*>,
                commandContext: CommandContext,
                key: Any
            ): Any? = null
        }
    }

    private companion object {
        const val REQUIRED_ROUTE = "/api/read-model/hosted-required-read-model-command"
        const val MISSING_KEY_ROUTE = "/api/read-model/hosted-missing-key-read-model-command"
        const val NULLABLE_ROUTE = "/api/read-model/hosted-nullable-read-model-command"
        const val OPTIONAL_ROUTE = "/api/read-model/hosted-java-optional-read-model-command"
    }
}

public data class HostedReadModel(public val id: String, public val value: String)
public data class HostedRequiredReadModelCommand(public val id: String)
public data class HostedMissingKeyReadModelCommand(public val id: String)
public data class HostedNullableReadModelCommand(public val id: String)

private class HostedReadModelModule : ArcArtifactModule(
    listOf(
        HostedReadModelHandler(HostedRequiredReadModelCommand::class.java, "/api/read-model/required", Resolution.REQUIRED),
        HostedReadModelHandler(HostedMissingKeyReadModelCommand::class.java, "/api/read-model/missing-key", Resolution.REQUIRED),
        HostedReadModelHandler(HostedNullableReadModelCommand::class.java, "/api/read-model/nullable", Resolution.NULLABLE),
        HostedReadModelHandler(HostedJavaOptionalReadModelCommand::class.java, "/api/read-model/optional", Resolution.OPTIONAL)
    ),
    emptyList()
)

private enum class Resolution { REQUIRED, NULLABLE, OPTIONAL }

private class HostedReadModelHandler(
    override val commandType: Class<*>,
    path: String,
    private val resolution: Resolution
) : CommandHandler {
    override val metadata = CommandDescriptor(
        name = commandType.simpleName,
        typeName = commandType.name,
        routeOptions = RouteOptions(path),
        location = listOf("read-model"),
        authorization = AuthorizationMetadata(allowAnonymous = true),
        explicitPath = path
    )

    override fun resolveCommandKey(command: Any): Any? = when (command) {
        is HostedMissingKeyReadModelCommand -> null
        is HostedRequiredReadModelCommand -> command.id
        is HostedNullableReadModelCommand -> command.id
        is HostedJavaOptionalReadModelCommand -> command.id()
        else -> null
    }

    override suspend fun invoke(context: CommandContext): Any {
        val resolver = CommandHandlerArgumentResolver(context)
        return when (resolution) {
            Resolution.REQUIRED -> resolver.resolve(HostedReadModel::class.java, "handle", "current").value
            Resolution.NULLABLE -> "kotlin:${resolver.resolveNullable(HostedReadModel::class.java, "handle", "current")?.value ?: "none"}"
            Resolution.OPTIONAL -> "java:${resolver.resolveOptional(HostedReadModel::class.java, "handle", "current").map(HostedReadModel::value).orElse("none")}"
        }
    }
}
