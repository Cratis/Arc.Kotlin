// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.results.CommandResult
import io.cratis.arc.validation.IgnoreValidation
import io.cratis.arc.validation.IgnoreValidationValidator
import jakarta.validation.Valid
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory
import jakarta.validation.TraversableResolver
import jakarta.validation.Path
import java.lang.annotation.ElementType
import java.util.concurrent.atomic.AtomicInteger
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer

internal class ArcIgnoreValidationTests {
    private fun runner() = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java, ArcValidationAutoConfiguration::class.java))
        .withBean(CommandHandlerRegistry::class.java, { ConcurrentCommandHandlerRegistry().apply { register(Handler()) } })
        .withBean(QueryPerformerRegistry::class.java, { ConcurrentQueryPerformerRegistry() })

    @Test
    fun `actual Boot default factory adapts before getter access without replacing validator bean`() {
        runner().run { context ->
            assertTrue(context.isRunning, context.startupFailure?.stackTraceToString())
            val applicationValidator = context.getBean(Validator::class.java)
            assertTrue(applicationValidator is ValidatorFactory)
            val input = Input()
            val result = runBlocking { context.getBean("arcJakartaBeanValidationCommandFilter", CommandFilter::class.java).execute(command(input)) }
            assertEquals(listOf("sibling"), result.validationResults.single().members)
            assertEquals(0, input.reads)
            assertSame(applicationValidator, context.getBean(Validator::class.java))
        }
    }

    @Test
    fun `Boot application configured resolver remains the delegate rather than provider default`() {
        val calls = AtomicInteger()
        val delegate = object : TraversableResolver {
            override fun isReachable(owner: Any?, property: Path.Node, root: Class<*>, path: Path, element: ElementType): Boolean {
                calls.incrementAndGet()
                return property.name != "sibling"
            }
            override fun isCascadable(owner: Any?, property: Path.Node, root: Class<*>, path: Path, element: ElementType): Boolean = true
        }
        runner().withBean(ValidationConfigurationCustomizer::class.java, { ValidationConfigurationCustomizer { it.traversableResolver(delegate) } })
            .run { context ->
                assertTrue(context.isRunning, context.startupFailure?.stackTraceToString())
                val factory = context.getBean(Validator::class.java) as ValidatorFactory
                assertSame(delegate, factory.traversableResolver)
                val input = Input()
                val result = runBlocking { context.getBean("arcJakartaBeanValidationCommandFilter", CommandFilter::class.java).execute(command(input)) }
                assertTrue(result.isSuccess)
                assertEquals(0, input.reads)
                assertTrue(calls.get() > 0)
            }
    }

    @Test
    fun `opaque validator fails startup for discoverable ignored model even with unrelated factory`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val opaque = factory.validator
            runner().withBean(Validator::class.java, { opaque })
                .withBean(ValidatorFactory::class.java, { factory }).run { context ->
                    val failure = requireNotNull(context.startupFailure)
                    assertTrue(failure.stackTraceToString().contains("cannot guarantee pre-access @IgnoreValidation"))
                    assertTrue(failure.stackTraceToString().contains("Input.ignored"))
                    assertTrue(failure.stackTraceToString().contains("IgnoreValidationValidator.fromFactory"))
                }
        }
    }

    @Test
    fun `custom validator exposing a factory does not silently lose its validator behavior`() {
        val custom = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(Validator::class.java, ValidatorFactory::class.java)) { _, method, _ ->
            error("Custom validator must not be replaced or invoked: ${method.name}")
        } as Validator
        val capability = JakartaValidationCapability(custom)
        assertSame(custom, capability.validator)
        assertThrows(IllegalStateException::class.java) { capability.requireSupport(Input::class.java) }
    }

    @Test
    fun `opaque validators remain usable for unaffected models and fail before imperative ignored input`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val configuration = ArcValidationAutoConfiguration()
            val filter = configuration.arcJakartaBeanValidationCommandFilter(factory.validator)
            runBlocking { assertFalse(filter.execute(command(Plain())).isSuccess) }
            val input = Input()
            assertThrows(IllegalStateException::class.java) { runBlocking { filter.execute(command(input)) } }
            assertEquals(0, input.reads)
        }
    }

    @Test
    fun `explicit factory adapter preserves custom bean and Arc named filter backoff`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val integrated = IgnoreValidationValidator.fromFactory(factory)
            val applicationFilter = object : CommandFilter {
                override suspend fun execute(context: CommandContext): CommandResult<*> = CommandResult.success(context.correlationId)
            }
            runner().withBean(Validator::class.java, { integrated })
                .withBean("arcJakartaBeanValidationCommandFilter", CommandFilter::class.java, { applicationFilter })
                .run { context ->
                    assertTrue(context.isRunning, context.startupFailure?.stackTraceToString())
                    assertSame(integrated, context.getBean(Validator::class.java))
                    assertSame(applicationFilter, context.getBean("arcJakartaBeanValidationCommandFilter"))
                }
            val opaque = factory.validator
            runner().withBean(Validator::class.java, { opaque })
                .withBean("arcJakartaBeanValidationCommandFilter", CommandFilter::class.java, { applicationFilter })
                .run { context ->
                    assertTrue(context.isRunning, context.startupFailure?.stackTraceToString())
                    assertSame(opaque, context.getBean(Validator::class.java))
                    assertSame(applicationFilter, context.getBean("arcJakartaBeanValidationCommandFilter"))
                }
            // Arc did not own/close the application factory.
            assertEquals(1, factory.validator.validate(Plain()).size)
        }
    }

    @Test
    fun `Arc query executable cascades ignore member not executable parameter`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val performer = object : QueryPerformer {
                override val descriptor = QueryDescriptor("find", QueryOwner::class.java.name, "kotlin.String", listOf(
                    ParameterDescriptor("input", Input::class.java.name, validateRecursively = true),
                    ParameterDescriptor("parameter", "kotlin.String")))
                override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
                override suspend fun perform(context: QueryContext): Any = error("must not invoke")
            }
            val registry = ConcurrentQueryPerformerRegistry().apply { register(performer) }
            val filter = ArcValidationAutoConfiguration().arcJakartaBeanValidationQueryFilter(IgnoreValidationValidator.fromFactory(factory), registry)
            val input = Input()
            val request = QueryRequest(performer.fullyQualifiedName, mapOf("input" to input, "parameter" to ""))
            val context = QueryContext(UUID.randomUUID(), request, performer.fullyQualifiedName, ArcPrincipal.anonymous(), null, null,
                object : ServiceResolver {
                    override fun <T : Any> resolve(type: Class<T>): T? = if (type == QueryOwner::class.java) type.cast(QueryOwner()) else null
                }, null, false)
            val result = runBlocking { filter.execute(context) }
            assertEquals(setOf("input.sibling", "parameter"), result.validationResults.flatMap { it.members }.toSet())
            assertEquals(0, input.reads)
        }
    }

    class QueryOwner {
        fun find(@Valid input: Input, @NotBlank parameter: String): String = input.sibling + parameter
    }

    @Test
    fun `opaque executable root containers are checked before Jakarta can cascade their elements`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val input = Input()
            val capability = JakartaValidationCapability(factory.validator)
            for (root in listOf(listOf(input), arrayOf(input), mapOf("key" to input), listOf(listOf(input)))) {
                assertThrows(IllegalStateException::class.java) { capability.requireArgumentSupport(root) }
                assertEquals(0, input.reads)
            }
        }
    }

    @Test
    fun `opaque dynamic cascades fail before reading parent getter rather than finding ignores too late`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val input = DynamicInput()
            val filter = ArcValidationAutoConfiguration().arcJakartaBeanValidationCommandFilter(factory.validator)
            val failure = assertThrows(IllegalStateException::class.java) { runBlocking { filter.execute(command(input)) } }
            assertTrue(failure.message.orEmpty().contains("unprovable runtime cascade"))
            assertEquals(0, input.reads)
        }
    }

    class DynamicInput {
        var reads = 0
        @get:Valid val child: Any get() { reads++; return Input() }
    }

    @Test
    fun `missing optional Boot validation module does not install a fallback factory`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ArcValidationAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("org.springframework.boot.validation"))
            .run { context ->
                assertTrue(context.isRunning)
                assertTrue(context.getBeansOfType(Validator::class.java).isEmpty())
                assertTrue(context.getBeansOfType(ValidatorFactory::class.java).isEmpty())
                assertFalse(context.containsBean("arcJakartaBeanValidationCommandFilter"))
            }
    }

    @Test
    fun `missing Jakarta classes keeps configuration optional`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(ArcValidationAutoConfiguration::class.java))
            .withClassLoader(FilteredClassLoader("jakarta.validation"))
            .run { context ->
                assertTrue(context.isRunning)
                assertFalse(context.containsBean("arcJakartaBeanValidationCommandFilter"))
            }
    }

    class Input {
        var reads = 0
        @get:IgnoreValidation @get:Valid @get:NotNull
        val ignored: Plain get() { reads++; error("ignored getter read") }
        @field:NotBlank val sibling = ""
    }
    class Plain(@field:NotBlank val name: String = "")
    private class Handler : CommandHandler {
        override val commandType = Input::class.java
        override val metadata = CommandDescriptor("Input", Input::class.java.name)
        override suspend fun invoke(context: CommandContext): Any? = null
    }
    private fun command(value: Any) = CommandContext(UUID.randomUUID(), value, value.javaClass, ArcPrincipal.anonymous(),
        serviceResolver = object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null })
}
