// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.validation.ConceptValidator
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ValidationFiltersTest {
    @Test
    fun `command filter runs every matching validator and preserves order`() = runBlocking {
        val calls = mutableListOf<String>()
        val filter = DefaultCommandValidationFilter(
            listOf(commandValidator("first", calls), otherCommandValidator(calls), commandValidator("second", calls))
        )
        val result = filter.execute(commandContext())
        assertEquals(listOf("first", "second"), calls)
        assertEquals(listOf("first", "second"), result.validationResults.map { it.message })
    }

    @Test
    fun `validator failures retain exception as validatorFailed and cancellation is rethrown`() = runBlocking {
        val failure = IllegalStateException("private detail")
        val failing = object : CommandValidator<TestCommand> {
            override val commandType = TestCommand::class.java
            override suspend fun validate(command: TestCommand, context: CommandContext): List<ValidationResult> =
                throw failure
        }
        val result = DefaultCommandValidationFilter(listOf(failing)).execute(commandContext())
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, result.validationResults.single().reason)
        assertEquals("The value could not be validated.", result.validationResults.single().message)
        assertEquals(null, result.validationResults.single().state)

        val cancelling = object : CommandValidator<TestCommand> {
            override val commandType = TestCommand::class.java
            override suspend fun validate(command: TestCommand, context: CommandContext): List<ValidationResult> =
                throw CancellationException("cancel")
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { DefaultCommandValidationFilter(listOf(cancelling)).execute(commandContext()) }
        }
    }

    @Test
    fun `query filter runs matching and global validators and isolates failures`() = runBlocking {
        val calls = mutableListOf<String>()
        val global = queryValidator(null, "global", calls)
        val matching = queryValidator(queryName, "matching", calls)
        val nonMatching = queryValidator(FullyQualifiedQueryName("other.query"), "other", calls)
        val failure = IllegalArgumentException("failure")
        val failing = object : QueryValidator {
            override val queryName = this@ValidationFiltersTest.queryName
            override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> =
                throw failure
        }
        val result = DefaultQueryValidationFilter(listOf(global, nonMatching, matching, failing)).execute(queryContext())
        assertEquals(listOf("global", "matching"), calls)
        assertEquals(
            listOf("global", "matching", "The value could not be validated."),
            result.validationResults.map { it.message }
        )
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, result.validationResults.last().reason)
        assertEquals(null, result.validationResults.last().state)
    }

    @Test
    fun `concept validators apply to command properties nested values collections and query arguments`() = runBlocking {
        val conceptValidator = object : ConceptValidator<EmailAddress> {
            override val conceptType = EmailAddress::class.java
            override fun validate(concept: EmailAddress): List<ValidationResult> =
                if (concept.value().contains('@')) emptyList()
                else listOf(
                    ValidationResult(
                        ValidationResultSeverity.Error,
                        "Email is invalid",
                        members = listOf("value")
                    )
                )
        }
        val command = ConceptCommand(
            EmailAddress("invalid"),
            NestedConcept(EmailAddress("also-invalid")),
            listOf(EmailAddress("valid@example.com"), EmailAddress("third-invalid"))
        )
        val commandContext = CommandContext(
            UUID.randomUUID(),
            command,
            ConceptCommand::class.java,
            ArcPrincipal.anonymous(),
            serviceResolver = EmptyServices()
        )
        val commandResult = DefaultCommandValidationFilter(emptyList(), listOf(conceptValidator)).execute(commandContext)
        assertEquals(
            listOf("alternatives[1]", "email", "nested.email"),
            commandResult.validationResults.flatMap { result -> result.members }
        )

        val request = QueryRequest(queryName, mapOf("addresses" to listOf(EmailAddress("invalid"))))
        val queryContext = QueryContext(
            UUID.randomUUID(),
            request,
            queryName,
            ArcPrincipal.anonymous(),
            null,
            null,
            EmptyServices(),
            null,
            false
        )
        val queryResult = DefaultQueryValidationFilter(emptyList(), listOf(conceptValidator)).execute(queryContext)
        assertEquals(listOf("addresses[0]"), queryResult.validationResults.single().members)
    }

    @Test
    fun `concept traversal validates shared instances once and terminates collection cycles`() = runBlocking {
        var validations = 0
        val conceptValidator = object : ConceptValidator<EmailAddress> {
            override val conceptType = EmailAddress::class.java
            override fun validate(concept: EmailAddress): List<ValidationResult> {
                validations++
                return listOf(ValidationResult(ValidationResultSeverity.Error, "Email is invalid"))
            }
        }
        val shared = EmailAddress("invalid")
        val cycle = mutableListOf<Any?>()
        cycle.add(shared)
        cycle.add(cycle)
        val command = DuplicateCycleCommand(cycle, shared, shared)
        val context = CommandContext(
            UUID.randomUUID(),
            command,
            DuplicateCycleCommand::class.java,
            ArcPrincipal.anonymous(),
            serviceResolver = EmptyServices()
        )

        val result = DefaultCommandValidationFilter(emptyList(), listOf(conceptValidator)).execute(context)

        assertEquals(1, validations)
        assertEquals(listOf("cycle[0]"), result.validationResults.single().members)
    }

    @Test
    fun `query validator cancellation is rethrown`() {
        val validator = object : QueryValidator {
            override val queryName = this@ValidationFiltersTest.queryName
            override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> =
                throw CancellationException("cancel")
        }
        assertThrows(CancellationException::class.java) {
            runBlocking { DefaultQueryValidationFilter(listOf(validator)).execute(queryContext()) }
        }
    }

    private val queryName = FullyQualifiedQueryName("io.cratis.Tests.all")

    private fun commandValidator(
        name: String,
        calls: MutableList<String>
    ): CommandValidator<TestCommand> = object : CommandValidator<TestCommand> {
        override val commandType = TestCommand::class.java
        override suspend fun validate(command: TestCommand, context: CommandContext): List<ValidationResult> {
            calls.add(name)
            return listOf(validation(name))
        }
    }

    private fun otherCommandValidator(calls: MutableList<String>): CommandValidator<OtherCommand> =
        object : CommandValidator<OtherCommand> {
            override val commandType = OtherCommand::class.java
            override suspend fun validate(command: OtherCommand, context: CommandContext): List<ValidationResult> {
                calls.add("other")
                return emptyList()
            }
        }

    private fun queryValidator(
        name: FullyQualifiedQueryName?,
        message: String,
        calls: MutableList<String>
    ): QueryValidator = object : QueryValidator {
        override val queryName = name
        override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> {
            calls.add(message)
            return listOf(validation(message))
        }
    }

    private fun commandContext(): CommandContext = CommandContext(
        UUID.randomUUID(),
        TestCommand("value"),
        TestCommand::class.java,
        ArcPrincipal.anonymous(),
        serviceResolver = EmptyServices()
    )

    private fun queryContext(): QueryContext = QueryContext(
        UUID.randomUUID(),
        QueryRequest(queryName),
        queryName,
        ArcPrincipal.anonymous(),
        null,
        null,
        EmptyServices(),
        null,
        false
    )

    private fun validation(message: String): ValidationResult =
        ValidationResult(ValidationResultSeverity.Error, message)

    private data class TestCommand(val value: String)
    private data class EmailAddress(private val rawValue: String) : ConceptAs<String> {
        override fun value(): String = rawValue
    }
    private data class NestedConcept(val email: EmailAddress)
    private data class ConceptCommand(
        val email: EmailAddress,
        val nested: NestedConcept,
        val alternatives: List<EmailAddress>
    )
    private data class DuplicateCycleCommand(
        val cycle: List<Any?>,
        val duplicate: EmailAddress,
        val shared: EmailAddress
    )
    private data object OtherCommand

    private class EmptyServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}
