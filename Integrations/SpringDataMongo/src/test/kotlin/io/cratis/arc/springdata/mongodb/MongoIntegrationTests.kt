// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import com.mongodb.client.MongoClients
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerArgumentResolver
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.queries.ReadModelForCommandResolverRegistry
import io.cratis.arc.springboot.SpringServiceResolver
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory

class MongoIntegrationTests {
    private lateinit var server: MongoServer
    private lateinit var client: com.mongodb.client.MongoClient
    private lateinit var context: GenericApplicationContext
    private lateinit var repository: MongoTaskRepository
    private lateinit var resolver: MongoCommandReadModelResolver
    private lateinit var handlers: ConcurrentCommandHandlerRegistry

    @BeforeEach
    fun setUp() {
        server = MongoServer(MemoryBackend())
        val address = server.bind()
        client = MongoClients.create("mongodb://${address.hostString}:${address.port}")
        val template = MongoTemplate(client, "arc")
        val mappingContext = MongoMappingContext().also { it.afterPropertiesSet() }
        handlers = ConcurrentCommandHandlerRegistry()
        resolver = DefaultMongoCommandReadModelResolver(template, mappingContext, handlers)
        repository = MongoRepositoryFactory(template).getRepository(MongoTaskRepository::class.java)
        context = GenericApplicationContext().also { applicationContext ->
            applicationContext.beanFactory.registerSingleton("mongoTaskRepository", repository)
            applicationContext.refresh()
        }
    }

    @AfterEach
    fun tearDown() {
        context.close()
        client.close()
        server.shutdownNow()
    }

    @Test
    fun `Spring repository injection and command key lookup use the Mongo store`() {
        val command = RenameMongoTask("task-1")
        handlers.register(RenameMongoTaskHandler())
        repository.save(MongoTaskReadModel("task-1", "Stored"))

        val injected = SpringServiceResolver(context).resolve(MongoTaskRepository::class.java)
        val resolved = resolver.resolve(MongoTaskReadModel::class.java, command)

        assertSame(repository, injected)
        assertEquals("Stored", resolved?.title)
    }

    @Test
    fun `real Mongo repository applies pageable sorting and preserves page totals`() {
        repository.deleteAll()
        repository.saveAll(
            listOf(
                MongoTaskReadModel("task-1", "Alpha"),
                MongoTaskReadModel("task-2", "Charlie"),
                MongoTaskReadModel("task-3", "Bravo")
            )
        )

        val springPage = repository.findAll(PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "title")))
        val queryPage = MongoQueryPageAdapter.toQueryPage(springPage)

        assertEquals(listOf("Charlie", "Bravo"), springPage.content.map(MongoTaskReadModel::getTitle))
        assertEquals(3, springPage.totalElements)
        assertEquals(2, springPage.totalPages)
        assertEquals(listOf("Charlie", "Bravo"), queryPage.items.map(MongoTaskReadModel::getTitle))
        assertEquals(3, queryPage.totalItems)
    }

    @Test
    fun `contextual resolver and generated argument seam isolate the same identifier across real tenant databases`() =
        runBlocking {
            val tenantA = MongoTemplate(client, "tenant-a")
            val tenantB = MongoTemplate(client, "tenant-b")
            tenantA.save(MongoTaskReadModel("same-id", "Tenant A"))
            tenantB.save(MongoTaskReadModel("same-id", "Tenant B"))
            val mappingContext = MongoMappingContext().also {
                it.setInitialEntitySet(setOf(MongoTaskReadModel::class.java))
                it.afterPropertiesSet()
            }
            val tenantResolver = TenantAwareMongoOperationsResolver { tenantId ->
                when (tenantId) {
                    "tenant-a" -> TenantMongoOperations(tenantId, tenantA)
                    "tenant-b" -> TenantMongoOperations(tenantId, tenantB)
                    else -> throw IllegalArgumentException("Unknown tenant $tenantId")
                }
            }
            val provider = MongoReadModelForCommandResolver(mappingContext, tenantResolver, true)
            val registry = ReadModelForCommandResolverRegistry(listOf(provider))

            suspend fun resolve(tenantId: String): MongoTaskReadModel {
                val services = object : ServiceResolver {
                    override fun <T : Any> resolve(type: Class<T>): T? =
                        if (type == ReadModelForCommandResolverRegistry::class.java) type.cast(registry) else null
                }
                val context = CommandContext(
                    UUID.randomUUID(),
                    RenameMongoTask("same-id"),
                    RenameMongoTask::class.java,
                    ArcPrincipal.anonymous(),
                    tenantId = tenantId,
                    serviceResolver = services,
                    commandKey = "same-id"
                )
                return CommandHandlerArgumentResolver(context).resolve(
                    MongoTaskReadModel::class.java,
                    "handle",
                    "current"
                )
            }

            assertEquals("Tenant A", resolve("tenant-a").title)
            assertEquals("Tenant B", resolve("tenant-b").title)
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                runBlocking { resolve("unknown") }
            }
        }
}

private data class RenameMongoTask(val id: String)

private class RenameMongoTaskHandler : CommandHandler {
    override val commandType: Class<*> = RenameMongoTask::class.java
    override val metadata: CommandDescriptor = CommandDescriptor("RenameMongoTask", commandType.name)
    override fun resolveCommandKey(command: Any): Any = (command as RenameMongoTask).id
    override suspend fun invoke(context: CommandContext): Any? = null
}
