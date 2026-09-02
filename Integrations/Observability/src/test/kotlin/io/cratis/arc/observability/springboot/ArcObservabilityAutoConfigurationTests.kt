// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.results.CommandResult
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class ArcObservabilityAutoConfigurationTests {
    @Test
    fun `auto configuration decorates an application pipeline when a registry exists`() {
        val pipeline = SuccessfulCommandPipeline()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcObservabilityAutoConfiguration::class.java))
            .withBean(ObservationRegistry::class.java, TestObservationRegistry::create)
            .withBean(CommandPipeline::class.java, { pipeline })
            .run { context ->
                val observed = context.getBean(CommandPipeline::class.java)
                assertNotSame(pipeline, observed)
                runBlocking { observed.execute(Any(), commandOptions()) }
                assertEquals(1, pipeline.executions)
            }
    }

    @Test
    fun `auto configuration backs off without a registry`() {
        val pipeline = SuccessfulCommandPipeline()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcObservabilityAutoConfiguration::class.java))
            .withBean(CommandPipeline::class.java, { pipeline })
            .run { context -> assertSame(pipeline, context.getBean(CommandPipeline::class.java)) }
    }

    @Test
    fun `auto configuration backs off for the no op registry`() {
        val pipeline = SuccessfulCommandPipeline()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcObservabilityAutoConfiguration::class.java))
            .withBean(ObservationRegistry::class.java, { ObservationRegistry.NOOP })
            .withBean(CommandPipeline::class.java, { pipeline })
            .run { context -> assertSame(pipeline, context.getBean(CommandPipeline::class.java)) }
    }

    @Test
    fun `auto configuration backs off when disabled`() {
        val pipeline = SuccessfulCommandPipeline()
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcObservabilityAutoConfiguration::class.java))
            .withPropertyValues("cratis.arc.observability.enabled=false")
            .withBean(ObservationRegistry::class.java, TestObservationRegistry::create)
            .withBean(CommandPipeline::class.java, { pipeline })
            .run { context -> assertSame(pipeline, context.getBean(CommandPipeline::class.java)) }
    }

    private fun commandOptions(): CommandExecutionOptions =
        CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), NoServices)

    private object NoServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }

    private class SuccessfulCommandPipeline : CommandPipeline {
        var executions: Int = 0
            private set

        override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> {
            executions++
            return CommandResult.success(options.correlationId)
        }

        override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> =
            CommandResult.success(options.correlationId)
    }
}
