// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

class MongoObservationExtensionTests {
    private lateinit var server: MongoServer
    private lateinit var client: com.mongodb.client.MongoClient
    private lateinit var template: MongoTemplate

    @BeforeEach
    fun setUp() {
        server = MongoServer(MemoryBackend())
        val address = server.bind()
        client = com.mongodb.client.MongoClients.create("mongodb://${address.hostString}:${address.port}")
        template = MongoTemplate(client, "arc-observe-ext")
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.shutdownNow()
    }

    @Test
    fun `reified observe extension emits same initial snapshot as class-based observe`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)

        val fromExtension = queries.observe<MongoTaskReadModel>().take(1).toList().first()
        val fromClassBased = queries.observe(MongoTaskReadModel::class.java).take(1).toList().first()

        assertEquals(fromClassBased.map(MongoTaskReadModel::getId), fromExtension.map(MongoTaskReadModel::getId))
    }

    @Test
    fun `reified observe extension with query applies filter`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        template.save(MongoTaskReadModel("two", "Second"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)

        val result = queries.observe<MongoTaskReadModel>(Query.query(Criteria.where("title").`is`("First")))
            .take(1).toList().first()

        assertEquals(1, result.size)
        assertEquals("First", result[0].getTitle())
    }

    @Test
    fun `criteria-based observe extension filters correctly and agrees with Query-based form`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        template.save(MongoTaskReadModel("two", "Second"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)
        val criteria = Criteria.where("title").`is`("Second")

        val fromCriteria = queries.observe<MongoTaskReadModel>(criteria).take(1).toList().first()
        val fromQuery = queries.observe<MongoTaskReadModel>(Query.query(criteria)).take(1).toList().first()

        assertEquals(fromQuery.map(MongoTaskReadModel::getId), fromCriteria.map(MongoTaskReadModel::getId))
        assertEquals(1, fromCriteria.size)
        assertEquals("Second", fromCriteria[0].getTitle())
    }

    @Test
    fun `reified observeList extension emits same snapshot as class-based observeList`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        template.save(MongoTaskReadModel("two", "Second"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)

        val fromExtension = queries.observeList<MongoTaskReadModel>().take(1).toList().first()
        val fromClassBased = queries.observeList(MongoTaskReadModel::class.java).take(1).toList().first()

        assertEquals(fromClassBased.map(MongoTaskReadModel::getId).sorted(), fromExtension.map(MongoTaskReadModel::getId).sorted())
    }

    @Test
    fun `criteria-based observeList extension filters correctly`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        template.save(MongoTaskReadModel("two", "Second"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)

        val result = queries.observeList<MongoTaskReadModel>(Criteria.where("title").`is`("First"))
            .take(1).toList().first()

        assertEquals(1, result.size)
        assertEquals("one", result[0].getId())
    }

    @Test
    fun `reified observeSingle extension emits same result as class-based observeSingle`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)

        val fromExtension = queries.observeSingle<MongoTaskReadModel>().take(1).toList().first()
        val fromClassBased = queries.observeSingle(MongoTaskReadModel::class.java).take(1).toList().first()

        assertEquals(fromClassBased.getId(), fromExtension.getId())
    }

    @Test
    fun `criteria-based observeSingle extension filters correctly`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        template.save(MongoTaskReadModel("two", "Second"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)

        val result = queries.observeSingle<MongoTaskReadModel>(Criteria.where("_id").`is`("two"))
            .take(1).toList().first()

        assertEquals("two", result.getId())
    }

    @Test
    fun `reified observeById extension emits same result as class-based observeById`(): Unit = runBlocking {
        template.save(MongoTaskReadModel("one", "First"))
        val watcher = MongoChangeStreamWatcher { _, _, _ -> emptyFlow() }
        val queries = MongoObservableQuery({ template }, watcher)

        val fromExtension = queries.observeById<MongoTaskReadModel>("one").take(1).toList().first()
        val fromClassBased = queries.observeById(MongoTaskReadModel::class.java, "one").take(1).toList().first()

        assertEquals(fromClassBased.getId(), fromExtension.getId())
        assertEquals("one", fromExtension.getId())
    }
}
