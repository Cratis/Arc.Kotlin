// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

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
    fun `scenario collects bounded emissions and exposes neutral assertions`() = runBlocking {
        ObservableQueryScenario<String>(Performer())
            .collect(2)
            .shouldSucceed()
            .shouldHaveEmissionCount(2)
            .shouldHaveData(1, "second")
    }

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
