// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.contracts.fixtures.ImplicitVisibilityCommand
import io.cratis.arc.contracts.fixtures.ImplicitVisibilityView
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedImplicitVisibilityTest {
    @Test
    fun `ordinary Kotlin visibility preserves wire keys invocation descriptors manifest and generated proxies`(): Unit = runBlocking {
        val module = ContractTestsArcArtifactModule()
        val mapper = ArcObjectMapper.create()
        val manifest = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/cratis/arc/ContractTests.json"))
            .use { mapper.readValue(it.readAllBytes(), ArcArtifactManifest::class.java) }
        val handler = module.commandHandlers.single { it.commandType == ImplicitVisibilityCommand::class.java }
        val descriptor = handler.metadata
        assertEquals(listOf("value", "id", "title"), descriptor.properties.map { it.name })
        assertEquals(descriptor.properties, module.types.single { it.name == "ImplicitVisibilityCommand" }.properties)
        assertEquals(descriptor.properties, manifest.commands.single { it.name == "ImplicitVisibilityCommand" }.properties)
        assertEquals(listOf("length"), descriptor.properties.last().validationRules.map { it.ruleName })
        assertEquals(listOf("value"), module.interfaces.single { it.name == "ImplicitNamed" }.properties.map { it.name })
        val command = mapper.readValue("""{"value":"v","id":"key","title":"changed"}""", ImplicitVisibilityCommand::class.java)
        assertEquals("key", handler.resolveCommandKey(command))
        val services = object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
        val registry = ConcurrentCommandHandlerRegistry().also { it.register(handler) }
        val result = DefaultCommandPipeline(registry).execute(command, CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal(), services))
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals("vchangedkey", result.response)
        val performer = module.queryPerformers.single { it.descriptor.name == "findImplicit" }
        assertEquals(mapper.writeValueAsString(performer.descriptor),
            mapper.writeValueAsString(manifest.queries.single { it.name == "findImplicit" }))
        assertEquals(listOf("value"), performer.descriptor.parameters.map { it.name })
        val queries = ConcurrentQueryPerformerRegistry().also { it.register(performer) }
        val query = DefaultQueryPipeline(queries).perform(QueryRequest(performer.fullyQualifiedName, mapOf("value" to "query")),
            QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal(), services))
        assertTrue(query.isSuccess)
        assertEquals(ImplicitVisibilityView("query"), query.data)
        val proxies = File(System.getProperty("arc.contractTests.generatedProxies"))
        val proxy = proxies.resolve("ImplicitVisibilityCommand.ts").readText()
        assertTrue(proxy.contains("new PropertyDescriptor('id', String, false)"), proxy)
        assertTrue(proxy.contains("this.ruleFor(c => c.title).length(2, 30);"), proxy)
        val queryProxy = proxies.resolve("FindImplicit.ts").readText()
        assertTrue(queryProxy.contains("extends QueryFor<ImplicitVisibilityView, FindImplicitParameters>"), queryProxy)
        assertTrue(queryProxy.contains("value: string"), queryProxy)
    }
}
