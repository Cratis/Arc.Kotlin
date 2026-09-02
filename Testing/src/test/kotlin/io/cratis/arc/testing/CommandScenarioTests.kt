// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.require
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.tenancy.HeaderTenantIdResolver
import io.cratis.arc.tenancy.TenantResolutionContext
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandScenarioTests {
    @Test
    fun `executes exact module handler with services context filters scopes response and JSON round trip`() = runBlocking {
        val dependency = TestDependency("service")
        val original = TestCommand("value")
        val order = mutableListOf<String>()
        val handler = ManualCommandHandler { context ->
            order.add("handler")
            assertNotSame(original, context.command)
            TestResponse(context.serviceResolver.require(TestDependency::class.java).prefix)
        }
        val scope = object : CommandExecutionScope {
            override fun begin(context: CommandContext) {
                order.add("begin")
            }

            override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                order.add("complete")
                return null
            }
        }
        val correlationId = UUID.randomUUID()
        val scenario = CommandScenario<TestCommand>(ManualArtifactModule(handler), TestCommand::class.java)
            .addService(TestDependency::class.java, dependency)
            .withPrincipal(ArcPrincipal("Ada", true))
            .withTenant("tenant", "namespace")
            .withCorrelationId(correlationId)
            .addFilter(CommandFilter {
                order.add("filter")
                CommandResult.success(it.correlationId)
            })
            .addScope(scope)

        val result = scenario.execute(original)

        assertEquals(listOf("begin", "filter", "handler", "complete"), order)
        assertEquals(correlationId, result.result.correlationId)
        assertEquals("tenant", handler.lastContext?.tenantId)
        assertEquals("namespace", handler.lastContext?.tenantNamespace)
        assertEquals("service", result.shouldSucceed().shouldHaveResponse(TestResponse::class.java).value)
    }

    @Test
    fun `explicit tenant resolution context flows into command context`() = runBlocking {
        val handler = ManualCommandHandler()

        CommandScenario<TestCommand>(handler)
            .withTenantResolution(
                HeaderTenantIdResolver(),
                TenantResolutionContext(headers = mapOf("X-Cratis-Tenant-Id" to "resolved-tenant"))
            )
            .execute(TestCommand("value"))
            .shouldSucceed()

        assertEquals("resolved-tenant", handler.lastContext?.tenantId)
        assertEquals("resolved-tenant", handler.lastContext?.tenantNamespace)
    }

    @Test
    fun `validate runs authorization validation and filters without handler or scopes`() = runBlocking {
        val handler = ManualCommandHandler()
        var scopeBegun = false
        var customFilterInvoked = false
        val validator = object : CommandValidator<TestCommand> {
            override val commandType = TestCommand::class.java
            override suspend fun validate(command: TestCommand, context: CommandContext): List<ValidationResult> =
                listOf(
                    ValidationResult(
                        ValidationResultSeverity.Error,
                        "value is rejected",
                        listOf("value"),
                        reason = ValidationResultReasons.RULE
                    )
                )
        }
        val scenario = CommandScenario<TestCommand>(handler)
            .addValidator(validator)
            .addFilter(CommandFilter {
                customFilterInvoked = true
                CommandResult.success(it.correlationId)
            })
            .addScope(object : CommandExecutionScope {
                override fun begin(context: CommandContext) {
                    scopeBegun = true
                }

                override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? = null
            })

        val result = scenario.validate(TestCommand("bad"))

        result.shouldBeInvalid().shouldHaveValidation("value", "rejected", ValidationResultReasons.RULE)
        assertEquals(0, handler.invocationCount)
        assertFalse(customFilterInvoked)
        assertFalse(scopeBegun)
    }

    @Test
    fun `authorization policies and principal use the real authorization filter`() = runBlocking {
        val handler = ManualCommandHandler(AuthorizationMetadata(policy = "operator"))
        val unauthorized = CommandScenario<TestCommand>(handler)
            .addPolicy("operator") { AuthorizationResult.failure("operator required") }
            .withPrincipal(ArcPrincipal("Ada", true))
            .execute(TestCommand("one"))

        unauthorized.shouldBeUnauthorized().shouldFail().shouldHaveNoResponse()
        assertEquals(0, handler.invocationCount)

        val allowedHandler = ManualCommandHandler(AuthorizationMetadata(policy = "operator"))
        val allowed = CommandScenario<TestCommand>(allowedHandler)
            .addPolicy("operator") { AuthorizationResult.success() }
            .withPrincipal(ArcPrincipal("Ada", true))
            .execute(TestCommand("one"))

        allowed.shouldSucceed()
        assertEquals(1, allowedHandler.invocationCount)
    }

    @Test
    fun `response handlers and allowed severity are configurable`() = runBlocking {
        val control = Any()
        val handler = ManualCommandHandler { control }
        val responseHandler = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean = value === control

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
                CommandResult.invalid(
                    context.correlationId,
                    listOf(ValidationResult(ValidationResultSeverity.Warning, "handled warning"))
                )
        }
        val result = CommandScenario<TestCommand>(handler)
            .addResponseHandler(responseHandler)
            .withAllowedValidationSeverity(ValidationResultSeverity.Unknown)
            .execute(TestCommand("one"))

        result.shouldBeInvalid().shouldHaveValidation(message = "handled warning")
        Unit
    }

    @Test
    fun `serialization round trip is default and can be disabled`() = runBlocking {
        val handler = ManualCommandHandler()
        val invalid = ThrowingSerializationCommandHandler()

        assertThrows(Exception::class.java) {
            runBlocking { CommandScenario<ThrowingSerializationCommand>(invalid).execute(ThrowingSerializationCommand()) }
        }

        val result = CommandScenario<ThrowingSerializationCommand>(invalid)
            .withSerializationRoundTrip(false)
            .execute(ThrowingSerializationCommand())

        result.shouldSucceed()
        assertEquals(1, invalid.invocationCount)
        assertSame(handler.commandType, TestCommand::class.java)
    }

    @Test
    fun `assertion failures contain expectation and readable actual summary`() {
        val result = CommandScenarioResult(CommandResult.success(UUID.randomUUID()))

        val failure = assertThrows(AssertionError::class.java) { result.shouldBeUnauthorized() }
        CommandScenarioResult(CommandResult.error(UUID.randomUUID(), "handler exploded"))
            .shouldHaveExceptionMessage("exploded")

        assertTrue(failure.message!!.contains("Expected the command to be unauthorized"))
        assertTrue(failure.message!!.contains("CommandResult("))
        assertTrue(failure.message!!.contains("success=true"))
    }

    @Test
    fun `service resolver and artifact helper reject duplicates and missing selections`() {
        val resolver = ScenarioServiceResolver.builder()
            .put(TestDependency::class.java, TestDependency("one"))
            .build()
        assertEquals("one", resolver.resolve(TestDependency::class.java)?.prefix)
        assertThrows(IllegalArgumentException::class.java) {
            resolver.put(TestDependency::class.java, TestDependency("two"))
        }

        val artifacts = ScenarioArtifactRegistry().register(ManualArtifactModule())
        assertThrows(ScenarioSetupException::class.java) { artifacts.command(String::class.java) }
        assertThrows(ScenarioSetupException::class.java) { artifacts.register(ManualArtifactModule()) }
    }

    private class ThrowingSerializationCommand {
        val broken: String get() = error("cannot serialize")
    }

    private class ThrowingSerializationCommandHandler : io.cratis.arc.commands.CommandHandler {
        override val commandType: Class<*> = ThrowingSerializationCommand::class.java
        override val metadata = io.cratis.arc.metadata.CommandDescriptor(
            "ThrowingSerializationCommand",
            commandType.name,
            authorization = AuthorizationMetadata(allowAnonymous = true)
        )
        var invocationCount = 0
            private set

        override suspend fun invoke(context: CommandContext): Any? {
            invocationCount++
            return null
        }
    }
}
