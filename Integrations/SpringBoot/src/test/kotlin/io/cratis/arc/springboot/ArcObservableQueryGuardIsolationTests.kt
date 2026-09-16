// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.GuardObservableQueryEmission
import io.cratis.arc.queries.ObservableQueryEmissionContext
import io.cratis.arc.queries.ObservableQueryEmissionGuards
import io.cratis.arc.queries.ObservableQueryEmissionVerdict
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule

internal class ArcObservableQueryGuardIsolationTests {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))

    @Test
    fun `guard factory retains authoritative application mapper and fresh concept values`() {
        var reads = 0
        val mapper = JsonMapper.builder().addModule(SimpleModule()
            .addSerializer(HostId::class.java, object : ValueSerializer<HostId>() {
                override fun serialize(value: HostId, generator: JsonGenerator, context: SerializationContext) {
                    generator.writeString("custom:${value.value()}")
                }
            })
            .addDeserializer(HostId::class.java, object : ValueDeserializer<HostId>() {
                override fun deserialize(parser: JsonParser, context: DeserializationContext): HostId {
                    reads++
                    require(parser.string.startsWith("custom:"))
                    return HostId(parser.string.removePrefix("custom:"))
                }
            })).build()
        val original = HostId("one")
        val seen = mutableListOf<HostId>()
        runner.withBean(ObjectMapper::class.java, { mapper })
            .withBean("firstGuard", GuardObservableQueryEmission::class.java, { BlockingObservableQueryEmissionGuard {
                seen.add(it.arguments["value"] as HostId); ObservableQueryEmissionVerdict.ALLOW
            } })
            .withBean("secondGuard", GuardObservableQueryEmission::class.java, { BlockingObservableQueryEmissionGuard {
                seen.add(it.arguments["value"] as HostId); ObservableQueryEmissionVerdict.ALLOW
            } })
            .run { context ->
                assertSame(mapper, context.getBean(ObjectMapper::class.java))
                val result = runBlocking { context.getBean(ObservableQueryEmissionGuards::class.java).guard(emission(original)) }
                assertEquals(ObservableQueryEmissionVerdict.ALLOW, result)
                assertEquals(3, reads)
                assertEquals(listOf("one", "one"), seen.map { it.value() })
                assertNotSame(original, seen[0])
                assertNotSame(seen[0], seen[1])
            }
    }

    @Test
    fun `application guard aggregator replaces default and host neutral context needs no mapper`() {
        val replacement = object : ObservableQueryEmissionGuards {
            override val hasGuards = true
            override suspend fun guard(context: ObservableQueryEmissionContext) = ObservableQueryEmissionVerdict.SUPPRESS
        }
        runner.withBean(ObservableQueryEmissionGuards::class.java, { replacement }).run { context ->
            assertSame(replacement, context.getBean(ObservableQueryEmissionGuards::class.java))
            assertEquals(1, context.getBeansOfType(ObservableQueryEmissionGuards::class.java).size)
        }
        runner.run { context ->
            assertEquals(false, context.getBean(ObservableQueryEmissionGuards::class.java).hasGuards)
        }
    }

    private fun emission(value: Any) = ObservableQueryEmissionContext(
        FullyQualifiedQueryName("Tests.observe"), mapOf("value" to value), ArcPrincipal.anonymous(), null, null, UUID.randomUUID(),
        object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }, true, null
    )

    internal class HostId(private val value: String) : ConceptAs<String> { override fun value(): String = value }
}
