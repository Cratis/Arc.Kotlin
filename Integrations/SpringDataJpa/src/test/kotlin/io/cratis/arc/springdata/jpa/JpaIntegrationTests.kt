// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(
    classes = [JpaIntegrationTestApplication::class],
    properties = [
        "spring.datasource.url=jdbc:h2:mem:arc-jpa;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "cratis.arc.spring-data.jpa.command-transactions-enabled=true"
    ]
)
class JpaIntegrationTests {
    @Autowired
    private lateinit var repository: JpaTaskRepository

    @Autowired
    private lateinit var serviceResolver: ServiceResolver

    @Autowired
    private lateinit var readModels: JpaCommandReadModelResolver

    @Autowired
    private lateinit var handlers: CommandHandlerRegistry

    @Autowired
    private lateinit var transactionScope: JpaCommandExecutionScope

    @Autowired
    private lateinit var observableQuery: JpaObservableQuery

    @Autowired
    private lateinit var changePublisher: DatabaseChangePublisher

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `Spring repository injection and command key lookup use the JPA store`() {
        val command = RenameJpaTask("task-1")
        handlers.register(RenameJpaTaskHandler())
        repository.saveAndFlush(JpaTaskReadModel("task-1", "Stored"))

        val injected = serviceResolver.resolve(JpaTaskRepository::class.java)
        val resolved = readModels.resolve(JpaTaskReadModel::class.java, command)

        assertNotNull(injected)
        assertNotNull(transactionScope)
        assertEquals("Stored", resolved?.title)
    }

    @Test
    fun `real JPA repository applies pageable sorting and preserves page totals`() {
        repository.deleteAll()
        repository.saveAllAndFlush(
            listOf(
                JpaTaskReadModel("task-1", "Alpha"),
                JpaTaskReadModel("task-2", "Charlie"),
                JpaTaskReadModel("task-3", "Bravo")
            )
        )

        val springPage = repository.findAll(PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "title")))
        val queryPage = JpaQueryPageAdapter.toQueryPage(springPage)

        assertEquals(listOf("Charlie", "Bravo"), springPage.content.map(JpaTaskReadModel::getTitle))
        assertEquals(3, springPage.totalElements)
        assertEquals(2, springPage.totalPages)
        assertEquals(listOf("Charlie", "Bravo"), queryPage.items.map(JpaTaskReadModel::getTitle))
        assertEquals(3, queryPage.totalItems)
    }

    @Test
    fun `rolled back H2 transaction does not publish a snapshot`() = runBlocking {
        repository.deleteAll()
        val values = observableQuery.observe(JpaTaskReadModel::class.java).produceIn(this)
        assertEquals(emptyList<JpaTaskReadModel>(), withTimeout(2_000) { values.receive() })
        delay(50)

        TransactionTemplate(transactionManager).executeWithoutResult { status ->
            repository.save(JpaTaskReadModel("task-rolled-back", "Rolled back"))
            changePublisher.publish(JpaTaskReadModel::class.java, null)
            status.setRollbackOnly()
        }

        assertNull(withTimeoutOrNull(250) { values.receive() })
        assertEquals(0, repository.count())
        values.cancel()
    }

    @Test
    fun `observable list emits initial state and committed H2 transaction in order`() = runBlocking {
        repository.deleteAll()
        val values = observableQuery.observe(JpaTaskReadModel::class.java).produceIn(this)
        assertEquals(emptyList<JpaTaskReadModel>(), withTimeout(2_000) { values.receive() })
        delay(50)

        TransactionTemplate(transactionManager).executeWithoutResult {
            repository.save(JpaTaskReadModel("task-observed", "Committed"))
            changePublisher.publish(JpaTaskReadModel::class.java, null)
            check(values.tryReceive().isFailure)
        }

        assertEquals("Committed", withTimeout(2_000) { values.receive() }.single().title)
        values.cancel()
    }
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableJpaRepositories(basePackageClasses = [JpaTaskRepository::class])
@EntityScan(basePackageClasses = [JpaTaskReadModel::class])
internal class JpaIntegrationTestApplication

private data class RenameJpaTask(val id: String)

private class RenameJpaTaskHandler : CommandHandler {
    override val commandType: Class<*> = RenameJpaTask::class.java
    override val metadata: CommandDescriptor = CommandDescriptor("RenameJpaTask", commandType.name)
    override fun resolveCommandKey(command: Any): Any = (command as RenameJpaTask).id
    override suspend fun invoke(context: CommandContext): Any? = null
}
