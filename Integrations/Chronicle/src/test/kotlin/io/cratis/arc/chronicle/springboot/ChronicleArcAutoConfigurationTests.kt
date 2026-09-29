// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle.springboot

import io.cratis.arc.chronicle.ChronicleCommandResponseValueHandler
import io.cratis.arc.chronicle.TenantEventStoreProvider
import io.cratis.arc.chronicle.TenantEventStoreResolver
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.naming.NamingPolicy
import io.cratis.arc.springdata.mongodb.springboot.ArcSpringDataMongoAutoConfiguration
import io.cratis.chronicle.ChronicleOptions
import io.cratis.chronicle.IChronicleClient
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.readModels.DefaultReadModelNamingPolicy
import io.cratis.chronicle.readModels.ReadModelNamingPolicy
import io.cratis.chronicle.spring.ChronicleAutoConfiguration
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class ChronicleArcAutoConfigurationTests {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChronicleArcAutoConfiguration::class.java))

    @Test
    fun `response handler and default resolver are registered when Arc and Chronicle beans are present`() {
        contextRunner
            .withBean(IEventStore::class.java, { mockk(relaxed = true) })
            .withBean(CommandHandlerRegistry::class.java, { ConcurrentCommandHandlerRegistry() })
            .run { context ->
                assertThat(context).hasSingleBean(TenantEventStoreResolver::class.java)
                assertThat(context).hasSingleBean(ChronicleCommandResponseValueHandler::class.java)
            }
    }

    @Test
    fun `default resolver composes tenant provider with default store`() {
        val defaultStore = mockk<IEventStore>()
        val tenantStore = mockk<IEventStore>()
        io.mockk.every { defaultStore.namespace } returns "default"
        io.mockk.every { tenantStore.namespace } returns "tenant-one"
        val provider = TenantEventStoreProvider { namespace -> tenantStore.takeIf { namespace == "tenant-one" } }

        contextRunner
            .withBean(IEventStore::class.java, { defaultStore })
            .withBean(TenantEventStoreProvider::class.java, { provider })
            .run { context ->
                val resolver = context.getBean(TenantEventStoreResolver::class.java)
                assertSame(defaultStore, resolver.resolve(null))
                assertSame(defaultStore, resolver.resolve("default"))
                assertSame(tenantStore, resolver.resolve("tenant-one"))
                assertThat(resolver.resolve("unknown")).isNull()
            }
    }

    @Test
    fun `application supplied resolver wins and enables handler without default store`() {
        val custom = TenantEventStoreResolver { null }

        contextRunner
            .withBean(TenantEventStoreResolver::class.java, { custom })
            .withBean(CommandHandlerRegistry::class.java, { ConcurrentCommandHandlerRegistry() })
            .run { context ->
                assertThat(context).hasSingleBean(TenantEventStoreResolver::class.java)
                assertSame(custom, context.getBean(TenantEventStoreResolver::class.java))
                assertThat(context).hasSingleBean(ChronicleCommandResponseValueHandler::class.java)
            }
    }

    @Test
    fun `response handler is absent without an event store or resolver bean`() {
        contextRunner
            .withBean(CommandHandlerRegistry::class.java, { ConcurrentCommandHandlerRegistry() })
            .run { context ->
                assertThat(context).doesNotHaveBean(TenantEventStoreResolver::class.java)
                assertThat(context).doesNotHaveBean(ChronicleCommandResponseValueHandler::class.java)
            }
    }

    @Test
    fun `application supplied response handler wins`() {
        val custom = mockk<ChronicleCommandResponseValueHandler>()
        contextRunner
            .withBean(IEventStore::class.java, { mockk(relaxed = true) })
            .withBean(CommandHandlerRegistry::class.java, { ConcurrentCommandHandlerRegistry() })
            .withBean(ChronicleCommandResponseValueHandler::class.java, { custom })
            .run { context ->
                assertThat(context).hasSingleBean(ChronicleCommandResponseValueHandler::class.java)
                assertSame(custom, context.getBean(ChronicleCommandResponseValueHandler::class.java))
            }
    }

    private val namingContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ArcSpringDataMongoAutoConfiguration::class.java,
                ChronicleAutoConfiguration::class.java,
                ChronicleArcAutoConfiguration::class.java
            )
        )
        .withPropertyValues("cratis.chronicle.auto-discover-and-register=false")
        .withBean(IChronicleClient::class.java, { mockk(relaxed = true) })
        .withBean(IEventStore::class.java, { mockk(relaxed = true) })

    @Test
    fun `chronicle options carry the Arc mongo naming policy so an author lands in the authors collection`() {
        namingContextRunner.run { context ->
            assertThat(context).hasSingleBean(ReadModelNamingPolicy::class.java)
            val policy = context.getBean(ChronicleOptions::class.java).readModelNamingPolicy
            assertEquals("Authors", policy.getReadModelName(Author::class.java))
            assertEquals("People", policy.getReadModelName(Person::class.java))
        }
    }

    @Test
    fun `a custom Arc naming policy flows through to the chronicle options`() {
        val custom = object : NamingPolicy {
            override fun getReadModelName(readModelType: Class<*>): String = "custom_${readModelType.simpleName}"
        }

        namingContextRunner
            .withBean(NamingPolicy::class.java, { custom })
            .run { context ->
                val policy = context.getBean(ChronicleOptions::class.java).readModelNamingPolicy
                assertEquals("custom_Author", policy.getReadModelName(Author::class.java))
            }
    }

    @Test
    fun `an application read model naming policy wins over the Arc naming policy`() {
        val application = ReadModelNamingPolicy { "app_${it.simpleName}" }

        namingContextRunner
            .withBean(ReadModelNamingPolicy::class.java, { application })
            .run { context ->
                assertThat(context).hasSingleBean(ReadModelNamingPolicy::class.java)
                assertSame(application, context.getBean(ReadModelNamingPolicy::class.java))
                val policy = context.getBean(ChronicleOptions::class.java).readModelNamingPolicy
                assertEquals("app_Author", policy.getReadModelName(Author::class.java))
            }
    }

    @Test
    fun `chronicle keeps its default read model naming without an Arc naming policy`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ChronicleAutoConfiguration::class.java, ChronicleArcAutoConfiguration::class.java)
            )
            .withPropertyValues("cratis.chronicle.auto-discover-and-register=false")
            .withBean(IChronicleClient::class.java, { mockk(relaxed = true) })
            .withBean(IEventStore::class.java, { mockk(relaxed = true) })
            .run { context ->
                assertThat(context).doesNotHaveBean(ReadModelNamingPolicy::class.java)
                assertSame(
                    DefaultReadModelNamingPolicy,
                    context.getBean(ChronicleOptions::class.java).readModelNamingPolicy
                )
                assertEquals(
                    "Author",
                    context.getBean(ChronicleOptions::class.java).readModelNamingPolicy.getReadModelName(Author::class.java)
                )
            }
    }

    @Test
    fun `the read model naming policy backs off when more than one Arc naming policy exists`() {
        val first = namingPolicy("first")
        val second = namingPolicy("second")

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChronicleArcAutoConfiguration::class.java))
            .withBean("first", NamingPolicy::class.java, { first })
            .withBean("second", NamingPolicy::class.java, { second })
            .run { context -> assertThat(context).doesNotHaveBean(ReadModelNamingPolicy::class.java) }
    }

    private fun namingPolicy(name: String): NamingPolicy = object : NamingPolicy {
        override fun getReadModelName(readModelType: Class<*>): String = name
    }

    private class Author

    private class Person
}
