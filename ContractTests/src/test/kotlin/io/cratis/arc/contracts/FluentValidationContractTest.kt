// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.contracts.fixtures.FluentContractCommand
import io.cratis.arc.contracts.fixtures.FluentContractInput
import io.cratis.arc.contracts.fixtures.FluentContractRules
import io.cratis.arc.contracts.fixtures.JavaFluentContractInput
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.testing.CommandScenario
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FluentValidationContractTest {
    @Test
    fun `generated module enforces Kotlin and Java declarations without explicit registration`() : Unit = runBlocking {
        val scenario = CommandScenario(ContractTestsArcArtifactModule(), FluentContractCommand::class.java)
        val result = scenario.execute(FluentContractCommand(FluentContractInput(""), JavaFluentContractInput("too long"))).result
        assertEquals(listOf("input.name", "javaInput.name"), result.validationResults.flatMap { it.members })
        scenario.execute(FluentContractCommand(FluentContractInput("ok"), JavaFluentContractInput("ok"))).shouldSucceed()
    }

    @Test
    fun `manual bean equivalent declaration does not duplicate generated feedback`() : Unit = runBlocking {
        val scenario = CommandScenario(ContractTestsArcArtifactModule(), FluentContractCommand::class.java).addModelValidator(FluentContractRules())
        val results = scenario.validate(FluentContractCommand(FluentContractInput(""), JavaFluentContractInput("ok"))).result.validationResults
        assertEquals(1, results.size)
        assertEquals(listOf("input.name"), results.single().members)
    }
}
