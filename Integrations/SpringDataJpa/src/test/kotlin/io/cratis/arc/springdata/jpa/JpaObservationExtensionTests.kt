// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class JpaObservationExtensionTests {

    @Test
    fun `reified observe extension emits same snapshot as class-based observe`(): Unit = runBlocking {
        val store = H2JpaStore("ext-observe")
        store.use { h2 ->
            h2.store(JpaTaskReadModel("t1", "Task One"))
            val notifier = DatabaseChangeNotifier { _, _ -> emptyFlow() }
            val queries = JpaObservableQuery(h2.entityManagerFactory, notifier)

            val fromExtension = queries.observe<JpaTaskReadModel>().take(1).toList().first()
            val fromClassBased = queries.observe(JpaTaskReadModel::class.java).take(1).toList().first()

            assertEquals(fromClassBased.map(JpaTaskReadModel::getId), fromExtension.map(JpaTaskReadModel::getId))
            assertEquals(1, fromExtension.size)
            assertEquals("t1", fromExtension[0].getId())
            queries.close()
        }
    }

    @Test
    fun `reified observeList extension emits same snapshot as class-based observeList`(): Unit = runBlocking {
        val store = H2JpaStore("ext-observelist")
        store.use { h2 ->
            h2.store(JpaTaskReadModel("t1", "Task One"))
            h2.store(JpaTaskReadModel("t2", "Task Two"))
            val notifier = DatabaseChangeNotifier { _, _ -> emptyFlow() }
            val queries = JpaObservableQuery(h2.entityManagerFactory, notifier)

            val fromExtension = queries.observeList<JpaTaskReadModel>().take(1).toList().first()
            val fromClassBased = queries.observeList(JpaTaskReadModel::class.java).take(1).toList().first()

            assertEquals(fromClassBased.map(JpaTaskReadModel::getId).sorted(), fromExtension.map(JpaTaskReadModel::getId).sorted())
            assertEquals(2, fromExtension.size)
            queries.close()
        }
    }

    @Test
    fun `reified observeSingle extension emits first entity from snapshot`(): Unit = runBlocking {
        val store = H2JpaStore("ext-observesingle")
        store.use { h2 ->
            h2.store(JpaTaskReadModel("t1", "Task One"))
            val notifier = DatabaseChangeNotifier { _, _ -> emptyFlow() }
            val queries = JpaObservableQuery(h2.entityManagerFactory, notifier)

            val fromExtension = queries.observeSingle<JpaTaskReadModel>().take(1).toList().first()
            val fromClassBased = queries.observeSingle(JpaTaskReadModel::class.java).take(1).toList().first()

            assertEquals(fromClassBased.getId(), fromExtension.getId())
            queries.close()
        }
    }

    @Test
    fun `reified observeById extension emits same result as class-based observeById`(): Unit = runBlocking {
        val store = H2JpaStore("ext-observebyid")
        store.use { h2 ->
            h2.store(JpaTaskReadModel("t1", "Task One"))
            val notifier = DatabaseChangeNotifier { _, _ -> emptyFlow() }
            val queries = JpaObservableQuery(h2.entityManagerFactory, notifier)

            val fromExtension = queries.observeById<JpaTaskReadModel>("t1").take(1).toList().first()
            val fromClassBased = queries.observeById(JpaTaskReadModel::class.java, "t1").take(1).toList().first()

            assertEquals(fromClassBased.getId(), fromExtension.getId())
            assertEquals("t1", fromExtension.getId())
            queries.close()
        }
    }

    @Test
    fun `reified observe extension with custom query applies the provided snapshot query`(): Unit = runBlocking {
        val store = H2JpaStore("ext-customquery")
        store.use { h2 ->
            h2.store(JpaTaskReadModel("t1", "Task One"))
            h2.store(JpaTaskReadModel("t2", "Task Two"))
            val notifier = DatabaseChangeNotifier { _, _ -> emptyFlow() }
            val queries = JpaObservableQuery(h2.entityManagerFactory, notifier)
            val customQuery = JpaSnapshotQuery { em ->
                em.createQuery(
                    "select entity from JpaTaskReadModel entity where entity.id = 't1'",
                    JpaTaskReadModel::class.java
                ).resultList
            }

            val result = queries.observe<JpaTaskReadModel>(customQuery).take(1).toList().first()

            assertEquals(1, result.size)
            assertEquals("t1", result[0].getId())
            queries.close()
        }
    }
}
