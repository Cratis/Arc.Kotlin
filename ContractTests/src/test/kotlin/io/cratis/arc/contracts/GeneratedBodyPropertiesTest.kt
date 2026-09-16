// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.contracts.fixtures.BodyOutput
import io.cratis.arc.contracts.fixtures.KotlinBodyCommand
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.json.ArcCamelCase
import io.cratis.arc.json.ArcObjectMapper
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedBodyPropertiesTest {
    private val mapper = ArcObjectMapper.create()
    private val module = ContractTestsArcArtifactModule()
    private val handler = module.commandHandlers.single { it.commandType == KotlinBodyCommand::class.java }
    private val names = listOf("zulu", "alpha", "URLValue", "backed", "child", "id", "title")

    @Test
    fun `native command module and manifest preserve ordered properties exact constraints summaries and reachability`() {
        val bytes = requireNotNull(javaClass.classLoader.getResourceAsStream("META-INF/cratis/arc/ContractTests.json"))
            .use { it.readAllBytes() }
        val manifest = mapper.readValue(bytes, ArcArtifactManifest::class.java)
        assertEquals(8, manifest.formatVersion)
        assertEquals(names, handler.metadata.properties.map { it.name })
        assertEquals(handler.metadata.properties, manifest.commands.single { it.name == "KotlinBodyCommand" }.properties)
        assertEquals(handler.metadata.properties, module.types.single { it.name == "KotlinBodyCommand" }.properties)
        val id = handler.metadata.properties.single { it.name == "id" }
        assertTrue(id.isCommandKey)
        assertEquals("Stable body identifier.", id.summary)
        assertEquals(listOf("notEmpty"), id.validationRules.map { it.ruleName })
        assertEquals(listOf(emptyList<Any>()), id.validationRules.map { it.arguments })
        val title = handler.metadata.properties.single { it.name == "title" }
        assertEquals(listOf("length"), title.validationRules.map { it.ruleName })
        assertEquals(listOf(listOf("2", "30")), title.validationRules.map { it.arguments.map(Any::toString) })
        assertTrue(handler.metadata.properties.single { it.name == "child" }.validateRecursively)
        assertEquals(listOf("first", "last"), module.types.single { it.name == "BodyChild" }.properties.map { it.name })
        assertEquals(listOf("notEmpty"), module.types.single { it.name == "BodyChild" }.properties.last().validationRules.map { it.ruleName })
        assertEquals(listOf("base", "baseBody", "label"), module.types.single { it.name == "BodyBase" }.properties.map { it.name })
        val output = module.types.single { it.name == "BodyOutput" }
        assertEquals(listOf("id", "detail", "label"), output.properties.map { it.name })
        assertEquals("io.cratis.arc.contracts.fixtures.BodyBase", output.baseTypeName)
    }

    @Test
    fun `actual Jackson wire state reaches generated key resolution and invocation through the Kotlin pipeline`() = runBlocking {
        val command = mapper.readValue(
            """{"zulu":"z","alpha":"a","URLValue":"url-input","backed":"backed-input","child":{"first":"first","last":"last-input"},"id":"body-key","title":"title-input","secret":"attack"}""",
            KotlinBodyCommand::class.java
        )
        assertEquals("secret", command.secret)
        assertEquals("body-key", handler.resolveCommandKey(command))
        val wire = mapper.readTree(mapper.writeValueAsString(command))
        assertEquals(names.map { ArcCamelCase.convert(it) }.toSet(), wire.propertyNames().asSequence().toSet())
        assertFalse(wire.has("secret"))
        assertFalse(wire.has("computed"))
        val registry = ConcurrentCommandHandlerRegistry().also { it.register(handler) }
        val options = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal(), object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        })
        val result = DefaultCommandPipeline(registry).execute(command, options)
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        val response = result.response as BodyOutput
        assertEquals("body-key", response.id)
        assertEquals("title-input:backed-input:last-input:url-input", response.detail)
        val outputWire = mapper.readTree(mapper.writeValueAsString(response))
        assertEquals(setOf("base", "baseBody", "label", "id", "detail"), outputWire.propertyNames().asSequence().toSet())
        assertEquals("output-body-key", outputWire["label"].stringValue())
    }
}
