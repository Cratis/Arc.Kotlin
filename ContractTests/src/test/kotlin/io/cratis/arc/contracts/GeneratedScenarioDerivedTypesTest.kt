// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.contracts.fixtures.FixtureCircle
import io.cratis.arc.contracts.fixtures.FixtureRectangle
import io.cratis.arc.contracts.fixtures.JavaFixtureImplementation
import io.cratis.arc.contracts.fixtures.ScenarioShapeCommand
import io.cratis.arc.contracts.fixtures.ScenarioShapeSource
import io.cratis.arc.contracts.fixtures.ScenarioShapeView
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.QueryScenario
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

internal class GeneratedScenarioDerivedTypesTest {
    private val module = ContractTestsArcArtifactModule()
    private val created = Instant.parse("2025-01-02T03:04:05Z")
    private val view = ScenarioShapeView(
        FixtureCircle("circle", 2.0, created),
        FixtureRectangle("rectangle", 3.0, 4.0, created),
        JavaFixtureImplementation("java")
    )

    @Test
    fun `generated command round trips interface and abstract base properties by default`() = runBlocking {
        val response = CommandScenario(module, ScenarioShapeCommand::class.java)
            .withPrincipal(ArcPrincipal("tester", true))
            .execute(ScenarioShapeCommand(view.shape, view.base, view.javaContract))
            .shouldSucceed().shouldHaveResponse(ScenarioShapeView::class.java)

        assertEquals(view, response)
        assertNotSame(view.shape, response.shape)
        assertNotSame(view.base, response.base)
        assertNotSame(view.javaContract, response.javaContract)
    }

    @Test
    fun `generated query round trips nested polymorphic data by default`() = runBlocking {
        val response = query("nested").perform().shouldSucceed().shouldHaveData(ScenarioShapeView::class.java)

        assertEquals(view, response)
        assertNotSame(view, response)
        assertNotSame(view.shape, response.shape)
        assertNotSame(view.base, response.base)
        assertNotSame(view.javaContract, response.javaContract)
    }

    @Test
    fun `generated query round trips nested polymorphic list data by default`() = runBlocking {
        val data = query("list").perform().shouldSucceed().result.data as List<*>
        assertEquals(listOf(view), data)
        val copied = data.single() as ScenarioShapeView
        assertNotSame(view, copied)
        assertNotSame(view.shape, copied.shape)
    }

    private fun query(name: String) = QueryScenario<Any>(
        module, FullyQualifiedQueryName("${ScenarioShapeView::class.java.name}.$name")
    ).withPrincipal(ArcPrincipal("tester", true))
        .addService(ScenarioShapeSource::class.java, ScenarioShapeSource(view))
}
