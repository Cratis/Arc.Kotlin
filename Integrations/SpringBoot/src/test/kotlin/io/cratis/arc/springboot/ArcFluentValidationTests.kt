// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.validation.FluentModelValidator
import io.cratis.arc.validation.FluentValidationMember
import io.cratis.arc.validation.FluentValidatorRegistration
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class ArcFluentValidationTests {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ArcAutoConfiguration::class.java))

    @Test
    fun `module contribution enforces both filters without a validator bean or optional host libraries`() {
        runner.withClassLoader(FilteredClassLoader("jakarta.servlet", "org.springframework.web", "org.springframework.security", "jakarta.validation"))
            .withBean(Module::class.java, { Module() }).run { context ->
                assertNull(context.startupFailure)
                assertEquals(0, context.getBeansOfType(NameRules::class.java).size)
                runBlocking {
                    val command = CommandContext(UUID.randomUUID(), Input(""), Input::class.java, ArcPrincipal.anonymous(), serviceResolver = Services)
                    val request = QueryRequest(FullyQualifiedQueryName("fluent.query"), mapOf("input" to Input("")))
                    val query = QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
                    assertEquals(listOf("name"), context.getBean(DefaultCommandValidationFilter::class.java).execute(command).validationResults.single().members)
                    assertEquals(listOf("input.name"), context.getBean(DefaultQueryValidationFilter::class.java).execute(query).validationResults.single().members)
                }
            }
    }

    @Test
    fun `same validator Spring bean and module declaration execute once`() {
        runner.withBean(Module::class.java, { Module() }).withBean(NameRules::class.java, { NameRules() }).run { context ->
            assertNull(context.startupFailure)
            runBlocking {
                val command = CommandContext(UUID.randomUUID(), Input(""), Input::class.java, ArcPrincipal.anonymous(), serviceResolver = Services)
                assertEquals(1, context.getBean(DefaultCommandValidationFilter::class.java).execute(command).validationResults.size)
            }
        }
    }

    @Test
    fun `missing generated metadata fails startup rather than losing client agreement`() {
        runner.withBean(NameRules::class.java, { NameRules() }).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("has no compiler metadata contribution")
        }
    }

    @Test
    fun `discovery snapshots a mutable module contribution before either factory uses it`() {
        val registrations = Module().fluentValidators.toMutableList()
        val module = object : ArcArtifactModule(emptyList(), emptyList()) { override val fluentValidators = registrations }
        runner.withBean(ArcArtifactModule::class.java, { module }).run { context ->
            assertNull(context.startupFailure)
            registrations.clear()
            val discovered = context.getBean(ArcArtifactModules::class.java)
            assertEquals(1, io.cratis.arc.artifacts.ArcArtifactModuleRegistry.modelValidators(discovered.validationModules).size)
        }
    }

    @Test
    fun `existing filter bean backoff stays authoritative`() {
        val command = DefaultCommandValidationFilter(emptyList())
        val query = DefaultQueryValidationFilter(emptyList())
        runner.withBean(DefaultCommandValidationFilter::class.java, { command })
            .withBean(DefaultQueryValidationFilter::class.java, { query }).run { context ->
                assertSame(command, context.getBean(DefaultCommandValidationFilter::class.java))
                assertSame(query, context.getBean(DefaultQueryValidationFilter::class.java))
            }
    }

    class Input(val name: String)
    class NameRules : FluentModelValidator<Input>(Input::class.java) { init { ruleFor("name").notEmpty() } }
    class Module : ArcArtifactModule(emptyList(), emptyList()) {
        override val fluentValidators = listOf(FluentValidatorRegistration(NameRules(), Input::class.java, listOf(
            FluentValidationMember("name", String::class.java, listOf(ValidationRuleDescriptor("notEmpty")))
        )))
    }
    private object Services : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
}
