// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.validation.FluentModelValidator
import io.cratis.arc.validation.FluentValidationMember
import io.cratis.arc.validation.FluentValidatorRegistration
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class ScenarioFluentValidationTests {
    @Test
    fun `generated contribution enforces command validation query and observable without manual registration`() : Unit = runBlocking {
        val fixture = Fixture()
        val module = fixture.module()
        val principal = ArcPrincipal("tester", true)
        val command = CommandScenario(module, Input::class.java).withPrincipal(principal)
        val query = QueryScenario<String>(module, fixture.query.fullyQualifiedName).withPrincipal(principal)
        val observable = ObservableQueryScenario<String>(module, fixture.observable.fullyQualifiedName).withPrincipal(principal)
        val invalid = Input("")
        val feedback = listOf(command.execute(invalid).result.validationResults,
            command.validate(invalid).result.validationResults,
            query.perform(mapOf("input" to invalid)).result.validationResults,
            observable.collect(1, arguments = mapOf("input" to invalid)).shouldFail().validationResults)
        assertEquals(listOf("name", "name", "input.name", "input.name"), feedback.map { it.single().members.single() })
        assertEquals(0, fixture.invocations)
        command.execute(Input("valid")).shouldSucceed()
        command.validate(Input("valid")).shouldSucceed()
        query.perform(mapOf("input" to Input("valid"))).shouldSucceed()
        observable.collect(1, arguments = mapOf("input" to Input("valid"))).shouldSucceed()
        assertEquals(3, fixture.invocations)
    }

    @Test
    fun `manual contribution of same declaration is deduped and missing compiler registration fails`() : Unit = runBlocking {
        val fixture = Fixture()
        val principal = ArcPrincipal("tester", true)
        val scenario = CommandScenario(fixture.module(), Input::class.java).withPrincipal(principal).addModelValidator(NameRules())
        assertEquals(1, scenario.execute(Input("")).result.validationResults.size)
        val missing = CommandScenario<Input>(fixture.handler).withPrincipal(principal).addModelValidator(NameRules())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { missing.execute(Input("")) } }
    }

    @Test
    fun `scenario snapshots module contributions at registration`() : Unit = runBlocking {
        val fixture = Fixture()
        val registrations = fixture.module().fluentValidators.toMutableList()
        val module = object : ArcArtifactModule(listOf(fixture.handler), emptyList()) { override val fluentValidators = registrations }
        val scenario = CommandScenario(module, Input::class.java).withPrincipal(ArcPrincipal("tester", true))
        registrations.clear()
        assertEquals(1, scenario.execute(Input("")).result.validationResults.size)
        assertEquals(0, fixture.invocations)
    }

    data class Input(val name: String)
    class NameRules : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").notEmpty() } }
    private class Fixture {
        var invocations = 0
        val handler = object : CommandHandler {
            override val commandType = Input::class.java
            override val metadata = CommandDescriptor("Input", Input::class.java.name)
            override suspend fun invoke(context: CommandContext): Any { invocations++; return "handled" }
        }
        fun performer(transport: QueryTransportType) = object : QueryPerformer {
            override val fullyQualifiedName = FullyQualifiedQueryName("fluent.$transport")
            override val descriptor = QueryDescriptor(transport.name, "fluent", "kotlin.String",
                parameters = listOf(ParameterDescriptor("input", Input::class.java.name)), transport = transport)
            override suspend fun perform(context: QueryContext): Any {
                invocations++
                return if (transport == QueryTransportType.OBSERVABLE) flowOf("data") else "data"
            }
        }
        val query = performer(QueryTransportType.REQUEST_RESPONSE)
        val observable = performer(QueryTransportType.OBSERVABLE)
        fun module() = object : ArcArtifactModule(listOf(handler), listOf(query, observable)) {
            override val fluentValidators = listOf(FluentValidatorRegistration(NameRules(), Input::class.java, listOf(
                FluentValidationMember("name", String::class.java, listOf(ValidationRuleDescriptor("notEmpty")))
            )))
        }
    }
}
