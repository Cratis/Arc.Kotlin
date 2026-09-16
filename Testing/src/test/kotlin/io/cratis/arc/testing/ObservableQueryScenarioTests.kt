// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard
import io.cratis.arc.queries.ObservableQueryEmissionVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class ObservableQueryScenarioTests {
    @Test
    fun `scenario collects bounded emissions and exposes neutral assertions`(): Unit = runBlocking {
        ObservableQueryScenario<String>(Performer())
            .withPrincipal(ArcPrincipal("tester", true))
            .collect(2)
            .shouldSucceed()
            .shouldHaveEmissionCount(2)
            .shouldHaveData(1, "second")
    }

    @Test
    fun `scenario guards isolate mutable arrays and scalar concepts for each emission`(): Unit = runBlocking {
        val numbers = intArrayOf(1)
        val concept = ScenarioId("one")
        val observations = mutableListOf<Int>()
        val scenario = ObservableQueryScenario<String>(Performer()).withPrincipal(ArcPrincipal("tester", true))
            .addEmissionGuard(BlockingObservableQueryEmissionGuard {
                (it.arguments["numbers"] as IntArray)[0] = 99
                ObservableQueryEmissionVerdict.ALLOW
            })
            .addEmissionGuard(BlockingObservableQueryEmissionGuard {
                observations.add((it.arguments["numbers"] as IntArray).single())
                assertEquals("one", (it.arguments["id"] as ScenarioId).value())
                assertNotSame(concept, it.arguments["id"])
                ObservableQueryEmissionVerdict.ALLOW
            })
        scenario.collect(2, arguments = mapOf("numbers" to numbers, "id" to concept))
            .shouldSucceed().shouldHaveEmissionCount(2).shouldHaveData(1, "second")
        assertEquals(listOf(1, 1), observations)
        assertEquals(1, numbers.single())
        scenario.collect(2, arguments = mapOf("numbers" to numbers, "id" to Any())).shouldTerminateUnauthorized()
        assertEquals(listOf(1, 1), observations)
    }

    internal class ScenarioId(private val value: String) : ConceptAs<String> { override fun value(): String = value }

    private class Performer : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName("Tests.Observable.values")
        override val descriptor = QueryDescriptor(
            "values",
            "Tests.Observable",
            "kotlin.String",
            transport = QueryTransportType.OBSERVABLE
        )
        override suspend fun perform(context: QueryContext): Any = flowOf("first", "second", "third")
    }
}
