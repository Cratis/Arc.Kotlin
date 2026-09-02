// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.queries.BlockingReadModelForCommandResolver
import io.cratis.arc.queries.CanResolveReadModelForCommand
import io.cratis.arc.queries.MultipleReadModelResolversForCommandException
import io.cratis.arc.queries.ReadModelForCommandOwnership
import io.cratis.arc.queries.ReadModelForCommandResolverRegistry
import io.cratis.arc.springdata.mongodb.springboot.ArcSpringDataMongoAutoConfiguration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.mapping.MongoMappingContext

class MongoAutoConfigurationTests {
    @Test
    fun `optional tenancy configures fixed operations new provider and legacy compatibility resolver`() {
        runner().run { context ->
            assertFalse(context.failed())
            val operationsResolver = context.getBean(MongoOperationsResolver::class.java)
            assertFalse(operationsResolver is TenantAwareMongoOperationsResolver)
            val provider = context.getBean(MongoReadModelForCommandResolver::class.java)
            assertSame(provider, context.getBeansOfType(CanResolveReadModelForCommand::class.java).values.single())
            assertEquals(setOf(MongoTaskReadModel::class.java), provider.readModelTypes())
            assertEquals(1, context.getBeansOfType(MongoCommandReadModelResolver::class.java).size)
            assertTrue(context.getBeansOfType(MongoCommandExecutionScope::class.java).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { operationsResolver.resolve("tenant-a") }
        }
    }

    @Test
    fun `new provider does not back off for an unrelated core provider`() {
        val unrelated = fallbackProvider(String::class.java)
        runner().withBean("unrelatedCoreProvider", CanResolveReadModelForCommand::class.java, { unrelated }).run { context ->
            assertFalse(context.failed())
            assertNotNull(context.getBean(MongoReadModelForCommandResolver::class.java))
            assertEquals(2, context.getBeansOfType(CanResolveReadModelForCommand::class.java).size)
        }
    }

    @Test
    fun `required tenancy fails for mapped read models without a tenant aware resolver`() {
        runner().withPropertyValues("cratis.arc.tenancy.required=true").run { context ->
            assertTrue(context.failed())
            assertTrue(context.failureMessages().contains("exactly one TenantAwareMongoOperationsResolver"))
        }
    }

    @Test
    fun `required tenancy rejects a plain resolver`() {
        runner()
            .withPropertyValues("cratis.arc.tenancy.required=true")
            .withBean(MongoOperationsResolver::class.java, { MongoOperationsResolver { mock() } })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(context.failureMessages().contains("TenantAwareMongoOperationsResolver"))
            }
    }

    @Test
    fun `required tenancy accepts exactly one marker and omits fixed legacy and transaction beans`() {
        val operations = mock(MongoOperations::class.java)
        val tenantAware = TenantAwareMongoOperationsResolver { tenantId ->
            TenantMongoOperations(tenantId, operations)
        }
        runner()
            .withPropertyValues("cratis.arc.tenancy.required=true")
            .withBean(TenantAwareMongoOperationsResolver::class.java, { tenantAware })
            .withBean(MongoTransactionManager::class.java, { mock() })
            .run { context ->
                assertFalse(context.failed())
                assertSame(tenantAware, context.getBean(TenantAwareMongoOperationsResolver::class.java))
                val provider = context.getBean(MongoReadModelForCommandResolver::class.java)
                assertSame(tenantAware, provider.tenantOperationsResolver)
                assertNotNull(context.getBean(MongoOperationsResolver::class.java))
                assertTrue(context.getBeansOfType(MongoCommandReadModelResolver::class.java).isEmpty())
                assertTrue(context.getBeansOfType(MongoCommandExecutionScope::class.java).isEmpty())
            }
    }

    @Test
    fun `required tenancy rejects a custom contextual provider without required enforcement or with another resolver`() {
        val operations = mock(MongoOperations::class.java)
        val tenantAware = TenantAwareMongoOperationsResolver { tenantId ->
            TenantMongoOperations(tenantId, operations)
        }
        val otherTenantAware = TenantAwareMongoOperationsResolver { tenantId ->
            TenantMongoOperations(tenantId, operations)
        }
        val unsafeProvider = MongoReadModelForCommandResolver(mappingContext(), tenantAware, false)

        runner()
            .withPropertyValues("cratis.arc.tenancy.required=true")
            .withBean(TenantAwareMongoOperationsResolver::class.java, { otherTenantAware })
            .withBean(MongoReadModelForCommandResolver::class.java, { unsafeProvider })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(context.failureMessages().contains("tenancyRequired=true"))
            }
    }

    @Test
    fun `required tenancy rejects an unrelated plain observation resolver alongside the certified resolver`() {
        val operations = mock(MongoOperations::class.java)
        val tenantAware = TenantAwareMongoOperationsResolver { tenantId ->
            TenantMongoOperations(tenantId, operations)
        }

        runner()
            .withPropertyValues("cratis.arc.tenancy.required=true")
            .withBean(TenantAwareMongoOperationsResolver::class.java, { tenantAware })
            .withBean("unsafeObservationResolver", MongoOperationsResolver::class.java, {
                MongoOperationsResolver { operations }
            })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(context.failureMessages().contains("observation and change-stream resolution"))
            }
    }

    @Test
    fun `required tenancy rejects more than one tenant aware resolver`() {
        runner()
            .withPropertyValues("cratis.arc.tenancy.required=true")
            .withBean("tenantAResolver", TenantAwareMongoOperationsResolver::class.java, {
                TenantAwareMongoOperationsResolver { tenantId ->
                    TenantMongoOperations(tenantId, mock(MongoOperations::class.java))
                }
            })
            .withBean("tenantBResolver", TenantAwareMongoOperationsResolver::class.java, {
                TenantAwareMongoOperationsResolver { tenantId ->
                    TenantMongoOperations(tenantId, mock(MongoOperations::class.java))
                }
            })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(context.failureMessages().contains("at most one TenantAwareMongoOperationsResolver"))
            }
    }

    @Test
    fun `legacy resolver still backs off for an application legacy resolver in optional tenancy`() {
        val applicationResolver = object : MongoCommandReadModelResolver {
            override fun <T : Any> resolve(readModelType: Class<T>, command: Any): T? = null
        }
        runner().withBean(MongoCommandReadModelResolver::class.java, { applicationResolver }).run { context ->
            assertFalse(context.failed())
            assertSame(applicationResolver, context.getBeansOfType(MongoCommandReadModelResolver::class.java).values.single())
            assertNotNull(context.getBean(MongoReadModelForCommandResolver::class.java))
        }
    }

    @Test
    fun `real Source registry lets a declared Chronicle like provider win Mongo fallback`() {
        val declaredValue = MongoTaskReadModel("same-id", "declared")
        val declared = object : BlockingReadModelForCommandResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(MongoTaskReadModel::class.java)
            override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
            override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any =
                declaredValue
        }
        coreRunner()
            .withBean("chronicleLikeDeclared", CanResolveReadModelForCommand::class.java, { declared })
            .run { context ->
                assertFalse(context.failed(), context.failureMessages())
                assertNotNull(context.getBean(MongoReadModelForCommandResolver::class.java))
                val registry = context.getBean(ReadModelForCommandResolverRegistry::class.java)
                assertSame(declaredValue, registry.resolveBlocking(MongoTaskReadModel::class.java, commandContext("same-id")))
            }
    }

    @Test
    fun `real Source registry fails startup for an equal Mongo fallback collision`() {
        coreRunner()
            .withBean("otherFallback", CanResolveReadModelForCommand::class.java, {
                fallbackProvider(MongoTaskReadModel::class.java)
            })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(
                    context.hasCause(MultipleReadModelResolversForCommandException::class.java),
                    context.failureMessages()
                )
            }
    }

    @Test
    fun `transaction scope is configured only for an aligned fixed MongoTemplate`() {
        val factory = mock(MongoDatabaseFactory::class.java)
        val template = mock(MongoTemplate::class.java)
        val manager = mock(MongoTransactionManager::class.java)
        `when`(template.mongoDatabaseFactory).thenReturn(factory)
        `when`(manager.resourceFactory).thenReturn(factory)

        runner(template)
            .withPropertyValues("cratis.arc.spring-data.mongodb.command-transactions-enabled=true")
            .withBean(MongoTransactionManager::class.java, { manager })
            .run { context ->
                assertFalse(context.failed())
                assertEquals(1, context.getBeansOfType(MongoCommandExecutionScope::class.java).size)
            }
    }

    @Test
    fun `transaction opt in fails when fixed operations are not a MongoTemplate`() {
        runner()
            .withPropertyValues("cratis.arc.spring-data.mongodb.command-transactions-enabled=true")
            .withBean(MongoTransactionManager::class.java, { mock() })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(context.failureMessages().contains("MongoTemplate"))
            }
    }

    @Test
    fun `transaction opt in fails when Mongo resource factory identities differ`() {
        val template = mock(MongoTemplate::class.java)
        val manager = mock(MongoTransactionManager::class.java)
        `when`(template.mongoDatabaseFactory).thenReturn(mock(MongoDatabaseFactory::class.java))
        `when`(manager.resourceFactory).thenReturn(mock(MongoDatabaseFactory::class.java))

        runner(template)
            .withPropertyValues("cratis.arc.spring-data.mongodb.command-transactions-enabled=true")
            .withBean(MongoTransactionManager::class.java, { manager })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(context.failureMessages().contains("share one resource factory"))
            }
    }

    @Test
    fun `transaction opt in fails for a dynamic tenant resolver`() {
        val template = mock(MongoTemplate::class.java)
        val factory = mock(MongoDatabaseFactory::class.java)
        val manager = mock(MongoTransactionManager::class.java)
        `when`(template.mongoDatabaseFactory).thenReturn(factory)
        `when`(manager.resourceFactory).thenReturn(factory)

        runner(template)
            .withPropertyValues("cratis.arc.spring-data.mongodb.command-transactions-enabled=true")
            .withBean(TenantAwareMongoOperationsResolver::class.java, {
                TenantAwareMongoOperationsResolver { tenantId -> TenantMongoOperations(tenantId, template) }
            })
            .withBean(MongoTransactionManager::class.java, { manager })
            .run { context ->
                assertTrue(context.failed())
                assertTrue(context.failureMessages().contains("fixed operations resolver"))
            }
    }

    private fun runner(operations: MongoOperations = mock(MongoOperations::class.java)): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcSpringDataMongoAutoConfiguration::class.java))
            .withBean(MongoOperations::class.java, { operations })
            .withBean(MongoMappingContext::class.java, { mappingContext() })
            .withBean(CommandHandlerRegistry::class.java, { mock(CommandHandlerRegistry::class.java) })

    private fun coreRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcSpringDataMongoAutoConfiguration::class.java))
        .withUserConfiguration(ReadModelRegistryTestConfiguration::class.java)
        .withBean(MongoOperations::class.java, { mock(MongoOperations::class.java) })
        .withBean(MongoMappingContext::class.java, { mappingContext() })

    private fun mappingContext(): MongoMappingContext = MongoMappingContext().also { context ->
        context.setInitialEntitySet(setOf(MongoTaskReadModel::class.java))
        context.afterPropertiesSet()
    }

    private fun fallbackProvider(type: Class<*>): CanResolveReadModelForCommand =
        object : BlockingReadModelForCommandResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(type)
            override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.FALLBACK
            override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? = null
        }

    private fun commandContext(key: Any): CommandContext = CommandContext(
        UUID.randomUUID(),
        AutoConfigurationTestCommand,
        AutoConfigurationTestCommand::class.java,
        ArcPrincipal.anonymous(),
        serviceResolver = object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        },
        commandKey = key
    )

    private fun org.springframework.boot.test.context.assertj.AssertableApplicationContext.failed(): Boolean =
        startupFailure != null

    private fun org.springframework.boot.test.context.assertj.AssertableApplicationContext.failureMessages(): String =
        generateSequence(startupFailure) { it.cause }.mapNotNull { it.message }.joinToString("\n")

    private fun org.springframework.boot.test.context.assertj.AssertableApplicationContext.hasCause(
        type: Class<out Throwable>
    ): Boolean = generateSequence(startupFailure) { it.cause }.any(type::isInstance)
}

@Configuration(proxyBeanMethods = false)
private class ReadModelRegistryTestConfiguration {
    @Bean
    fun readModelForCommandResolverRegistry(
        resolvers: ObjectProvider<CanResolveReadModelForCommand>
    ): ReadModelForCommandResolverRegistry = ReadModelForCommandResolverRegistry(resolvers.orderedStream().toList())
}

private object AutoConfigurationTestCommand
