// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle.springboot

import io.cratis.arc.chronicle.ChronicleCommandResponseValueHandler
import io.cratis.arc.chronicle.TenantEventStoreProvider
import io.cratis.arc.chronicle.TenantEventStoreResolver
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.chronicle.IEventStore
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
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
}
