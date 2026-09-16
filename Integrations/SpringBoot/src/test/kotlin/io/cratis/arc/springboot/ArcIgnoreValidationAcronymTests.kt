// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.validation.IgnoreValidation
import io.cratis.arc.validation.IgnoreValidationValidator
import jakarta.validation.Valid
import jakarta.validation.Validation
import jakarta.validation.Validator
import jakarta.validation.constraints.NotBlank
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class ArcIgnoreValidationAcronymTests {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `opaque provider rejects manually registered acronym command at startup`(java: Boolean) {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val input = input(java)
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ArcValidationAutoConfiguration::class.java))
                .withBean(Validator::class.java, { factory.validator })
                .withBean(CommandHandlerRegistry::class.java, {
                    ConcurrentCommandHandlerRegistry().apply { register(handler(input.javaClass)) }
                })
                .withBean(QueryPerformerRegistry::class.java, { ConcurrentQueryPerformerRegistry() })
                .run { context ->
                    assertNotNull(context.startupFailure, "opaque provider must reject registered acronym model")
                    assertDiagnostic(requireNotNull(context.startupFailure).stackTraceToString(), input)
                    assertEquals(0, reads(input))
                }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `opaque provider rejects imperative acronym command before reading getter`(java: Boolean) {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val input = input(java)
            val selected = factory.validator
            assertSame(selected, JakartaValidationCapability(selected).validator)
            val filter = ArcValidationAutoConfiguration().arcJakartaBeanValidationCommandFilter(selected)
            try {
                val failure = assertThrows(IllegalStateException::class.java) { runBlocking { filter.execute(command(input)) } }
                assertDiagnostic(failure.message.orEmpty(), input)
            } finally {
                assertEquals(0, reads(input), "capability rejection must precede provider access")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `opaque provider rejects supplied acronym query before executable cascade reads getter`(java: Boolean) {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val input = input(java)
            val performer = performer(java, input.javaClass)
            val registry = ConcurrentQueryPerformerRegistry().apply { register(performer) }
            val filter = ArcValidationAutoConfiguration().arcJakartaBeanValidationQueryFilter(factory.validator, registry)
            try {
                val failure = assertThrows(IllegalStateException::class.java) {
                    runBlocking { filter.execute(query(input, performer)) }
                }
                assertDiagnostic(failure.message.orEmpty(), input)
            } finally {
                assertEquals(0, reads(input), "capability rejection must precede executable provider access")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `factory adapter keeps acronym command and query siblings active with zero ignored reads`(java: Boolean) {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val input = input(java)
            val adapted = IgnoreValidationValidator.fromFactory(factory)
            val configuration = ArcValidationAutoConfiguration()
            val commandResult = runBlocking { configuration.arcJakartaBeanValidationCommandFilter(adapted).execute(command(input)) }
            assertEquals(listOf("sibling"), commandResult.validationResults.flatMap { it.members })
            assertEquals(0, reads(input))
            val performer = performer(java, input.javaClass)
            val registry = ConcurrentQueryPerformerRegistry().apply { register(performer) }
            val queryResult = runBlocking {
                configuration.arcJakartaBeanValidationQueryFilter(adapted, registry).execute(query(input, performer))
            }
            assertEquals(listOf("input.sibling"), queryResult.validationResults.flatMap { it.members })
            assertEquals(0, reads(input))
            // Arc leaves the application-owned factory usable.
            assertEquals(1, factory.validator.validate(Plain()).size)
        }
    }

    class KotlinInput {
        var reads = 0
        @get:IgnoreValidation @get:NotBlank
        val URL: String get() { reads++; throw AssertionError("ignored Kotlin acronym getter read") }
        @field:NotBlank val sibling = ""
    }
    class Plain { @field:NotBlank val sibling = "" }
    class QueryOwner {
        fun kotlinInput(@Valid input: KotlinInput): String = input.sibling
        fun javaInput(@Valid input: IgnoreValidationAcronymJavaInput): String = input.sibling
    }
    private fun input(java: Boolean): Any = if (java) IgnoreValidationAcronymJavaInput() else KotlinInput()
    private fun reads(input: Any): Int = when (input) {
        is IgnoreValidationAcronymJavaInput -> input.reads
        is KotlinInput -> input.reads
        else -> error("unknown fixture")
    }
    private fun assertDiagnostic(message: String, input: Any) {
        assertTrue(message.contains("cannot guarantee pre-access @IgnoreValidation"), message)
        assertTrue(message.contains("${input.javaClass.name}.URL"), message)
        assertTrue(message.contains("IgnoreValidationValidator.fromFactory"), message)
    }
    private fun handler(type: Class<*>) = object : CommandHandler {
        override val commandType = type
        override val metadata = CommandDescriptor(type.simpleName, type.name)
        override suspend fun invoke(context: CommandContext): Any = error("must not invoke")
    }
    private fun performer(java: Boolean, type: Class<*>) = object : QueryPerformer {
        override val descriptor = QueryDescriptor(if (java) "javaInput" else "kotlinInput", QueryOwner::class.java.name,
            "kotlin.String", listOf(ParameterDescriptor("input", type.name, validateRecursively = true)))
        override val fullyQualifiedName = FullyQualifiedQueryName(descriptor.fullyQualifiedName)
        override suspend fun perform(context: QueryContext): Any = error("must not invoke")
    }
    private fun command(input: Any) = CommandContext(UUID.randomUUID(), input, input.javaClass, ArcPrincipal.anonymous(),
        serviceResolver = Services)
    private fun query(input: Any, performer: QueryPerformer): QueryContext {
        val request = QueryRequest(performer.fullyQualifiedName, mapOf("input" to input))
        return QueryContext(UUID.randomUUID(), request, request.queryName, ArcPrincipal.anonymous(), null, null, Services, null, false)
    }
    private object Services : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = if (type == QueryOwner::class.java) type.cast(QueryOwner()) else null
    }
}
