// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandPipelineTest {
    private val correlationId = UUID.fromString("9272472e-a18c-47c2-9e03-c92b4f774e18")
    private val principal = ArcPrincipal("Ada", true, linkedSetOf("operator"))
    private val services = MapServiceResolver()
    private val options = CommandExecutionOptions(
        correlationId,
        principal,
        services,
        "tenant-one",
        "tenant-one-namespace",
        null,
        true
    )

    @Test
    fun `missing handler is an exception result`() = runBlocking {
        val result = pipeline().execute(TestCommand("one"), options)

        assertFalse(result.isSuccess)
        assertEquals(listOf("No handler is registered for command '${TestCommand::class.java.name}'."), result.exceptionMessages)
    }

    @Test
    fun `validate requires a handler and invokes filters only`() = runBlocking {
        var handlerInvoked = false
        var filterInvoked = false
        val handler = TestHandler { handlerInvoked = true }
        val filter = CommandFilter {
            filterInvoked = true
            CommandResult.success(it.correlationId, "filter response must not escape")
        }
        val scope = RecordingScope(mutableListOf(), "scope")
        val result = pipeline(handler, listOf(filter), listOf(scope)).validate(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertTrue(filterInvoked)
        assertFalse(handlerInvoked)
        assertEquals(0, scope.beginCount)
        assertEquals(0, scope.completeCount)
        assertNull(result.response)
    }

    @Test
    fun `validate reports a missing handler without running filters`() = runBlocking {
        var filterInvoked = false
        val result = pipeline(filters = listOf(CommandFilter {
            filterInvoked = true
            CommandResult.success(it.correlationId)
        })).validate(TestCommand("one"), options)

        assertFalse(result.isSuccess)
        assertFalse(filterInvoked)
    }

    @Test
    fun `authorization filters run first with stable order inside both groups`() = runBlocking {
        val order = mutableListOf<String>()
        val filters = listOf(
            namedFilter("ordinary-one", order),
            namedAuthorizationFilter("authorization-one", order),
            namedFilter("ordinary-two", order),
            namedAuthorizationFilter("authorization-two", order)
        )

        pipeline(TestHandler(), filters).execute(TestCommand("one"), options)

        assertEquals(
            listOf("authorization-one", "authorization-two", "ordinary-one", "ordinary-two"),
            order
        )
    }

    @Test
    fun `filters stop at the first non-success result`() = runBlocking {
        val order = mutableListOf<String>()
        val filters = listOf(
            CommandFilter {
                order.add("first")
                CommandResult.invalid(it.correlationId, listOf(error("blocked")))
            },
            namedFilter("second", order)
        )
        val handler = TestHandler { error("must not run") }

        val result = pipeline(handler, filters).execute(TestCommand("one"), options)

        assertEquals(listOf("first"), order)
        assertEquals(0, handler.invocationCount)
        assertEquals(listOf("blocked"), result.validationResults.map { it.message })
    }

    @Test
    fun `throwing filter becomes a result and later scope failures are preserved`() = runBlocking {
        val scope = RecordingScope(mutableListOf(), "scope", completionFailure = IllegalArgumentException("scope failed"))
        val filter = CommandFilter { throw IllegalStateException("filter failed") }

        val result = pipeline(TestHandler(), listOf(filter), listOf(scope)).execute(TestCommand("one"), options)

        assertEquals(listOf("filter failed", "scope failed"), result.exceptionMessages)
        assertTrue(result.exceptionStackTrace.contains("IllegalStateException: filter failed"))
        assertTrue(result.exceptionStackTrace.contains("IllegalArgumentException: scope failed"))
    }

    @Test
    fun `validation severity uses strict numeric thresholds`() = runBlocking {
        val validation = listOf(
            ValidationResult(ValidationResultSeverity.Unknown, "unknown"),
            ValidationResult(ValidationResultSeverity.Information, "information"),
            ValidationResult(ValidationResultSeverity.Warning, "warning"),
            ValidationResult(ValidationResultSeverity.Error, "error")
        )
        val filter = CommandFilter { CommandResult.invalid(it.correlationId, validation) }

        suspend fun messages(allowed: ValidationResultSeverity?): List<String> {
            val result = pipeline(TestHandler(), listOf(filter)).validate(
                TestCommand("one"),
                CommandExecutionOptions(correlationId, principal, services, allowedValidationSeverity = allowed)
            )
            return result.validationResults.map { it.message }
        }

        assertEquals(listOf("error"), messages(null))
        assertEquals(listOf("information", "warning", "error"), messages(ValidationResultSeverity.Unknown))
        assertEquals(listOf("warning", "error"), messages(ValidationResultSeverity.Information))
        assertEquals(listOf("error"), messages(ValidationResultSeverity.Warning))
        assertEquals(emptyList<String>(), messages(ValidationResultSeverity.Error))
    }

    @Test
    fun `scopes begin in order and complete the same instances in reverse order`() = runBlocking {
        val order = mutableListOf<String>()
        val first = RecordingScope(order, "first")
        val second = RecordingScope(order, "second")

        val result = pipeline(TestHandler(), scopes = listOf(first, second)).execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals(listOf("begin:first", "begin:second", "complete:second", "complete:first"), order)
        assertEquals(1, first.beginCount)
        assertEquals(1, first.completeCount)
        assertEquals(1, second.beginCount)
        assertEquals(1, second.completeCount)
        assertSame(first.contextAtBegin, first.contextAtComplete)
        assertSame(second.contextAtBegin, second.contextAtComplete)
    }

    @Test
    fun `only scopes whose begin succeeded complete after a begin failure`() = runBlocking {
        val order = mutableListOf<String>()
        val first = RecordingScope(order, "first")
        val second = RecordingScope(order, "second", beginFailure = IllegalStateException("begin failed"))
        val third = RecordingScope(order, "third")

        val result = pipeline(TestHandler(), scopes = listOf(first, second, third)).execute(TestCommand("one"), options)

        assertEquals(listOf("begin:first", "begin:second", "complete:first"), order)
        assertEquals(1, first.completeCount)
        assertEquals(0, second.completeCount)
        assertEquals(0, third.beginCount)
        assertEquals(0, third.completeCount)
        assertEquals(listOf("begin failed"), result.exceptionMessages)
    }

    @Test
    fun `completion failures are isolated and clear a previously produced response`() = runBlocking {
        val order = mutableListOf<String>()
        val first = RecordingScope(order, "first")
        val second = RecordingScope(order, "second", completionFailure = IllegalStateException("commit failed"))
        val handler = TestHandler { "client response" }

        val result = pipeline(handler, scopes = listOf(first, second)).execute(TestCommand("one"), options)

        assertEquals(listOf("begin:first", "begin:second", "complete:second", "complete:first"), order)
        assertEquals(1, first.completeCount)
        assertEquals(1, second.completeCount)
        assertEquals(listOf("commit failed"), result.exceptionMessages)
        assertNull(result.response)
    }

    @Test
    fun `scope result fragments merge into the final immutable result`() = runBlocking {
        val scope = RecordingScope(
            mutableListOf(),
            "scope",
            completionResult = CommandResult.invalid(correlationId, listOf(error("completion rejected")))
        )
        val result = pipeline(TestHandler { "client response" }, scopes = listOf(scope))
            .execute(TestCommand("one"), options)

        assertEquals(listOf("completion rejected"), result.validationResults.map { it.message })
        assertNull(result.response)
    }

    @Test
    fun `normal unhandled handler value becomes the client response`() = runBlocking {
        val result = pipeline(TestHandler { "response" }).execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals("response", result.response)
    }

    @Test
    fun `all matching response handlers execute and merge their fragments`() = runBlocking {
        val calls = mutableListOf<String>()
        val first = controlHandler("first", calls, ValidationResultSeverity.Information)
        val second = controlHandler("second", calls, ValidationResultSeverity.Warning)
        val severityOptions = CommandExecutionOptions(
            correlationId,
            principal,
            services,
            allowedValidationSeverity = ValidationResultSeverity.Unknown
        )

        val result = pipeline(TestHandler { ControlValue }, responseHandlers = listOf(first, second))
            .execute(TestCommand("one"), severityOptions)

        assertEquals(listOf("first", "second"), calls)
        assertEquals(listOf("first", "second"), result.validationResults.map { it.message })
    }

    @Test
    fun `handled value result fragment cannot become the client response`() = runBlocking {
        val result = pipeline(
            TestHandler { ControlValue },
            responseHandlers = listOf(respondingControlHandler("handler response"))
        )
            .execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertNull(result.response)
    }

    @Test
    fun `handled value result fragment cannot replace the aggregate client response`() = runBlocking {
        val aggregate = CommandResponseValues.of(ControlValue, "client response")

        val result = pipeline(
            TestHandler { aggregate },
            responseHandlers = listOf(respondingControlHandler("handler response"))
        )
            .execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals("client response", result.response)
    }

    @Test
    fun `scalar custom response declaration does not suppress a custom collection`() = runBlocking {
        val response = listOf(CustomHandledValue)
        val result = pipeline(
            TestHandler { response },
            responseHandlers = listOf(ScalarCustomResponseHandler())
        ).execute(TestCommand("custom collection"), options)

        assertSame(response, result.response)
    }

    @Test
    fun `metadata client disposition preserves empty client list and array`() = runBlocking {
        val descriptor = responseDescriptor(
            Client::class.qualifiedName!!,
            isEnumerable = true,
            CommandResponseValueDisposition.CLIENT
        )
        val list = emptyList<Client>()
        val array = emptyArray<Client>()

        val listResult = executeMetadataResponse(list, descriptor)
        val arrayResult = executeMetadataResponse(array, descriptor)

        assertTrue(listResult.isSuccess)
        assertSame(list, listResult.response)
        assertTrue(arrayResult.isSuccess)
        assertSame(array, arrayResult.response)
    }

    @Test
    fun `metadata handled disposition consumes empty validation list and array`() = runBlocking {
        val descriptor = responseDescriptor(
            ValidationResult::class.qualifiedName!!,
            isEnumerable = true,
            CommandResponseValueDisposition.HANDLED
        )
        val list = emptyList<ValidationResult>()
        val array = emptyArray<ValidationResult>()

        val listResult = executeMetadataResponse(list, descriptor)
        val arrayResult = executeMetadataResponse(array, descriptor)

        assertTrue(listResult.isSuccess)
        assertNull(listResult.response)
        assertTrue(arrayResult.isSuccess)
        assertNull(arrayResult.response)
    }

    @Test
    fun `metadata re-evaluates handled value after installing a later client response`() = runBlocking {
        val client = Client("client")
        val calls = mutableListOf<String>()
        val dependent = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean =
                value === StaticallyHandled && context.response === client

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                calls.add("handled")
                return CommandResult.success(context.correlationId)
            }
        }
        val metadata = listOf(
            responseDescriptor(
                StaticallyHandled::class.qualifiedName!!,
                isEnumerable = false,
                CommandResponseValueDisposition.HANDLED
            ),
            responseDescriptor(
                Client::class.qualifiedName!!,
                isEnumerable = false,
                CommandResponseValueDisposition.CLIENT
            )
        )

        val result = pipeline(
            TestHandler(metadata) { Pair(StaticallyHandled, client) },
            responseHandlers = listOf(dependent)
        ).execute(TestCommand("handled before client"), options)

        assertTrue(result.isSuccess)
        assertSame(client, result.response)
        assertEquals(listOf("handled"), calls)
    }

    @Test
    fun `statically handled value fails closed when annotated handler is not registered`() = runBlocking {
        val absentHandler = AnnotatedStaticallyHandledResponseHandler()
        val descriptor = responseDescriptor(
            StaticallyHandled::class.qualifiedName!!,
            isEnumerable = false,
            CommandResponseValueDisposition.HANDLED
        )

        val result = pipeline(TestHandler(listOf(descriptor)) { StaticallyHandled })
            .execute(TestCommand("absent handler"), options)

        assertFalse(result.isSuccess)
        assertNull(result.response)
        assertEquals(0, absentHandler.invocationCount)
        assertEquals(
            listOf(
                "No command response value handler accepted the statically handled response value " +
                    "'${StaticallyHandled::class.qualifiedName}'."
            ),
            result.exceptionMessages
        )
    }

    @Test
    fun `nested command result control fragment does not consume a response descriptor`() = runBlocking {
        val client = Client("nested client")
        val handledValues = mutableListOf<Any>()
        val handledValueHandler = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean = value === StaticallyHandled

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                handledValues.add(value)
                return CommandResult.success(context.correlationId)
            }
        }
        val metadata = listOf(
            responseDescriptor(
                Client::class.qualifiedName!!,
                isEnumerable = false,
                CommandResponseValueDisposition.CLIENT
            ),
            responseDescriptor(
                StaticallyHandled::class.qualifiedName!!,
                isEnumerable = false,
                CommandResponseValueDisposition.HANDLED
            )
        )
        val response = Pair(CommandResult.success(UUID.randomUUID(), client), StaticallyHandled)

        val result = pipeline(
            TestHandler(metadata) { response },
            responseHandlers = listOf(handledValueHandler)
        ).execute(TestCommand("nested result"), options)

        assertTrue(result.isSuccess)
        assertSame(client, result.response)
        assertEquals(listOf(StaticallyHandled), handledValues)
    }

    @Test
    fun `manual handler without response metadata retains dynamic empty collection handling`() = runBlocking {
        val response = emptyList<Client>()

        val result = pipeline(TestHandler { response }).execute(TestCommand("legacy"), options)

        assertTrue(result.isSuccess)
        assertNull(result.response)
    }

    @Test
    fun `built-in validation result handling rejects the command`() = runBlocking {
        val result = pipeline(TestHandler { error("invalid return") }).execute(TestCommand("one"), options)

        assertEquals(listOf("invalid return"), result.validationResults.map { it.message })
        assertNull(result.response)
    }

    @Test
    fun `built-in validation collection handling preserves order`() = runBlocking {
        val result = pipeline(TestHandler { listOf(error("first"), error("second")) })
            .execute(TestCommand("one"), options)

        assertEquals(listOf("first", "second"), result.validationResults.map { it.message })
    }

    @Test
    fun `built-in validation array handling preserves order`() = runBlocking {
        val result = pipeline(TestHandler { arrayOf(error("first"), error("second")) })
            .execute(TestCommand("one"), options)

        assertEquals(listOf("first", "second"), result.validationResults.map { it.message })
    }

    @Test
    fun `built-in validation collection handler intentionally consumes empty collection shapes`() = runBlocking {
        val list = emptyList<ValidationResult>()
        val array = emptyArray<ValidationResult>()

        val listResult = pipeline(TestHandler { list }).execute(TestCommand("list"), options)
        val arrayResult = pipeline(TestHandler { array }).execute(TestCommand("array"), options)

        assertTrue(listResult.isSuccess)
        assertNull(listResult.response)
        assertTrue(arrayResult.isSuccess)
        assertNull(arrayResult.response)
    }

    @Test
    fun `built-in authorization result handling preserves failure reason`() = runBlocking {
        val result = pipeline(TestHandler { AuthorizationResult.failure("operator role required") })
            .execute(TestCommand("one"), options)

        assertFalse(result.isAuthorized)
        assertEquals("operator role required", result.authorizationFailureReason)
    }

    @Test
    fun `direct command result is handled as a control result including its response`() = runBlocking {
        val returned = CommandResult.success(UUID.randomUUID(), "direct response")
        val result = pipeline(TestHandler { returned }).execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals(correlationId, result.correlationId)
        assertEquals("direct response", result.response)
    }

    @Test
    fun `command result responses recursively use aggregate processing`() = runBlocking {
        val returned = CommandResult.success(
            UUID.randomUUID(),
            Pair("nested response", AuthorizationResult.success())
        )
        val result = pipeline(TestHandler { returned }).execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals("nested response", result.response)
    }

    @Test
    fun `command results nested in aggregates expose their response exactly once`() = runBlocking {
        val nested = CommandResult.success(UUID.randomUUID(), "nested response")
        val result = pipeline(TestHandler { Pair(nested, AuthorizationResult.success()) })
            .execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals("nested response", result.response)
    }

    @Test
    fun `aggregate sets response before re-evaluating dependent handlers`() = runBlocking {
        val calls = mutableListOf<String>()
        val dependent = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean =
                value === ControlValue && context.response == "client response"

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                calls.add("handled")
                return CommandResult.success(context.correlationId)
            }
        }
        val aggregate = CommandResponseValues.of("client response", ControlValue)

        val result = pipeline(TestHandler { aggregate }, responseHandlers = listOf(dependent))
            .execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals("client response", result.response)
        assertEquals(listOf("handled"), calls)
    }

    @Test
    fun `aggregate predicate failure is merged once while the predicate is re-evaluated`() = runBlocking {
        var predicateCalls = 0
        val throwing = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean {
                if (value !== ControlValue) return false
                predicateCalls++
                throw IllegalStateException("predicate failed")
            }

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
                throw IllegalStateException("must not handle")
        }

        val result = pipeline(
            TestHandler { CommandResponseValues.of(ControlValue) },
            responseHandlers = listOf(throwing)
        ).execute(TestCommand("one"), options)

        assertFalse(result.isSuccess)
        assertEquals(2, predicateCalls)
        assertEquals(listOf("predicate failed"), result.exceptionMessages)
    }

    @Test
    fun `aggregate response-dependent handlers are re-evaluated and invoked in declaration order`() = runBlocking {
        val predicateResponses = mutableListOf<Any?>()
        val calls = mutableListOf<String>()
        val dependent = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean {
                if (value !== ControlValue) return false
                predicateResponses.add(context.response)
                return context.response == "client response"
            }

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                calls.add("dependent")
                return CommandResult.success(context.correlationId)
            }
        }
        val unconditional = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean = value === ControlValue

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                calls.add("unconditional")
                return CommandResult.success(context.correlationId)
            }
        }
        val aggregate = CommandResponseValues.of(ControlValue, "client response")

        val result = pipeline(
            TestHandler { aggregate },
            responseHandlers = listOf(dependent, unconditional)
        ).execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals(listOf(null, "client response"), predicateResponses)
        assertEquals(listOf("dependent", "unconditional"), calls)
        assertEquals("client response", result.response)
    }

    @Test
    fun `aggregate processes handled values in declared order`() = runBlocking {
        val calls = mutableListOf<String>()
        val handler = object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean = value is OrderedControl

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                calls.add((value as OrderedControl).name)
                return CommandResult.success(context.correlationId)
            }
        }
        val aggregate = CommandResponseValues.of(OrderedControl("first"), OrderedControl("second"))

        val result = pipeline(TestHandler { aggregate }, responseHandlers = listOf(handler))
            .execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertEquals(listOf("first", "second"), calls)
        assertNull(result.response)
    }

    @Test
    fun `aggregate with multiple unhandled values fails deterministically`() = runBlocking {
        val aggregate = CommandResponseValues.of(42, "second")

        val result = pipeline(TestHandler { aggregate }).execute(TestCommand("one"), options)

        assertFalse(result.isSuccess)
        assertEquals(
            listOf("A command response aggregate contains multiple unhandled values: java.lang.Integer, java.lang.String"),
            result.exceptionMessages
        )
        assertNull(result.response)
    }

    @Test
    fun `cancellation is rethrown after all scopes complete`() {
        val order = mutableListOf<String>()
        val first = RecordingScope(order, "first")
        val second = RecordingScope(order, "second")
        val pipeline = pipeline(
            TestHandler { throw CancellationException("cancelled") },
            scopes = listOf(first, second)
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { pipeline.execute(TestCommand("one"), options) }
        }
        assertEquals("cancelled", thrown.message)
        assertEquals(listOf("begin:first", "begin:second", "complete:second", "complete:first"), order)
        assertTrue(second.resultsAtCompletion.single().isSuccess.not())
        assertTrue(first.resultsAtCompletion.single().isSuccess.not())
    }

    @Test
    fun `context carries every explicit host value`() = runBlocking {
        var received: CommandContext? = null
        val handler = TestHandler {
            received = it
            null
        }

        pipeline(handler).execute(TestCommand("one"), options)

        val context = requireNotNull(received)
        assertEquals(correlationId, context.correlationId)
        assertEquals(TestCommand::class.java, context.commandType)
        assertEquals(principal, context.principal)
        assertEquals("tenant-one", context.tenantId)
        assertEquals("tenant-one-namespace", context.tenantNamespace)
        assertEquals(null, context.allowedValidationSeverity)
        assertSame(services, context.serviceResolver)
        assertTrue(context.exposeExceptionDetails)
        assertTrue(context.executionToken != null)
    }

    @Test
    fun `context copies preserve one stable execution token`() = runBlocking {
        val scope = RecordingScope(mutableListOf(), "scope")
        val handler = object : TestHandler(operation = { "response" }) {
            override suspend fun prepare(context: CommandContext): CommandPreparation =
                CommandPreparation(listOf("provided"), CommandResult.success(context.correlationId))
        }

        val result = pipeline(handler, scopes = listOf(scope)).execute(TestCommand("one"), options)

        assertTrue(result.isSuccess)
        assertNotSame(scope.contextAtBegin, scope.contextAtComplete)
        assertSame(scope.contextAtBegin?.executionToken, scope.contextAtComplete?.executionToken)
    }

    @Test
    fun `preparation short circuits handle and validate skips preparation`() = runBlocking {
        var preparations = 0
        val handler = object : TestHandler() {
            override suspend fun prepare(context: CommandContext): CommandPreparation {
                preparations++
                return CommandPreparation.from(error("prepare rejected"), context)
            }
        }
        val pipeline = pipeline(handler)

        val validation = pipeline.validate(TestCommand("validate"), options)
        val execution = pipeline.execute(TestCommand("execute"), options)

        assertTrue(validation.isSuccess)
        assertEquals(1, preparations)
        assertEquals(0, handler.invocationCount)
        assertEquals(listOf("prepare rejected"), execution.validationResults.map { it.message })
    }

    @Test
    fun `pair triple and one of responses use aggregate response processing`() = runBlocking {
        val pair = pipeline(TestHandler { Pair("response", AuthorizationResult.success()) })
            .execute(TestCommand("pair"), options)
        val triple = pipeline(TestHandler {
            ArcOneOf.of(Triple(AuthorizationResult.success(), "response", AuthorizationResult.success()))
        }).execute(TestCommand("triple"), options)

        assertEquals("response", pair.response)
        assertTrue(pair.isSuccess)
        assertEquals("response", triple.response)
        assertTrue(triple.isSuccess)
    }

    @Test
    fun `context value providers run in order before scopes and filters`() = runBlocking {
        var received: Map<String, Any>? = null
        val providers = listOf(
            CommandContextValuesProvider { mapOf("first" to 1, "shared" to "old") },
            CommandContextValuesProvider { mapOf("second" to 2, "shared" to "new") }
        )
        val filter = CommandFilter {
            received = it.values
            CommandResult.success(it.correlationId)
        }

        pipeline(TestHandler(), listOf(filter), contextValuesProviders = providers)
            .execute(TestCommand("one"), options)

        assertEquals(mapOf("first" to 1, "shared" to "new", "second" to 2), received)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (received as MutableMap<String, Any>)["changed"] = true
        }
    }

    @Test
    fun `nested same-coroutine execution creates a child token in the shared root`() = runBlocking {
        lateinit var commandPipeline: DefaultCommandPipeline
        var outerToken: CommandExecutionToken? = null
        var childToken: CommandExecutionToken? = null
        val handler = TestHandler { context ->
            if ((context.command as TestCommand).value == "outer") {
                outerToken = context.executionToken
                val child = commandPipeline.execute(TestCommand("child"), options)
                assertTrue(child.isSuccess)
            } else {
                childToken = context.executionToken
            }
            null
        }
        commandPipeline = pipeline(handler)

        val result = commandPipeline.execute(TestCommand("outer"), options)

        assertTrue(result.isSuccess)
        assertTrue(outerToken != null)
        assertTrue(childToken != null)
        assertNotSame(outerToken, childToken)
        assertSame(outerToken?.executionOwner, childToken?.executionOwner)
        assertSame(outerToken, outerToken?.rootToken)
        assertSame(outerToken, childToken?.rootToken)
    }

    @Test
    fun `live token supplied by nested factory joins with a distinct child frame`() = runBlocking {
        lateinit var commandPipeline: DefaultCommandPipeline
        var outerToken: CommandExecutionToken? = null
        var childToken: CommandExecutionToken? = null
        val handler = TestHandler { context ->
            if ((context.command as TestCommand).value == "outer") {
                outerToken = context.executionToken
                commandPipeline.execute(TestCommand("child"), CommandExecutionOptions.nested(context))
            } else {
                childToken = context.executionToken
                null
            }
        }
        commandPipeline = pipeline(handler)

        val result = commandPipeline.execute(TestCommand("outer"), options)

        assertTrue(result.isSuccess)
        assertNotSame(outerToken, childToken)
        assertSame(outerToken?.executionOwner, childToken?.executionOwner)
    }

    @Test
    fun `separate top-level executions create independent roots`() = runBlocking {
        val tokens = mutableListOf<CommandExecutionToken>()
        val commandPipeline = pipeline(TestHandler { context ->
            tokens.add(requireNotNull(context.executionToken))
            null
        })

        commandPipeline.execute(TestCommand("first"), options)
        commandPipeline.execute(TestCommand("second"), options)

        assertNotSame(tokens[0], tokens[1])
        assertNotSame(tokens[0].rootToken, tokens[1].rootToken)
        assertNotSame(tokens[0].executionOwner, tokens[1].executionOwner)
    }

    @Test
    fun `detached execution without an inherited token starts an independent root`() = runBlocking {
        lateinit var commandPipeline: DefaultCommandPipeline
        var outerToken: CommandExecutionToken? = null
        var detachedToken: CommandExecutionToken? = null
        val detachedScope = CoroutineScope(SupervisorJob())
        val handler = TestHandler { context ->
            if ((context.command as TestCommand).value == "outer") {
                outerToken = context.executionToken
                detachedScope.async {
                    commandPipeline.execute(TestCommand("detached"), options)
                }.await()
            } else {
                detachedToken = context.executionToken
                null
            }
        }
        commandPipeline = pipeline(handler)

        val result = commandPipeline.execute(TestCommand("outer"), options)
        detachedScope.cancel()

        assertTrue(result.isSuccess)
        assertNotSame(outerToken, detachedToken)
        assertNotSame(outerToken?.executionOwner, detachedToken?.executionOwner)
    }

    @Test
    fun `root sealing marks an unawaited nested execution rollback only`() = runBlocking {
        lateinit var commandPipeline: DefaultCommandPipeline
        val childStarted = CompletableDeferred<Unit>()
        val releaseChild = CompletableDeferred<Unit>()
        val detachedScope = CoroutineScope(SupervisorJob())
        lateinit var child: kotlinx.coroutines.Deferred<CommandResult<*>>
        val handler = TestHandler { context ->
            if ((context.command as TestCommand).value == "outer") {
                child = detachedScope.async {
                    commandPipeline.execute(TestCommand("child"), CommandExecutionOptions.nested(context))
                }
                childStarted.await()
                "must be cleared"
            } else {
                childStarted.complete(Unit)
                releaseChild.await()
                null
            }
        }
        commandPipeline = pipeline(handler)

        val result = commandPipeline.execute(TestCommand("outer"), options)
        releaseChild.complete(Unit)
        val childResult = child.await()
        detachedScope.cancel()

        assertFalse(result.isSuccess)
        assertNull(result.response)
        assertFalse(childResult.isSuccess)
    }

    @Test
    fun `root rejects a nested join once execution scope completion starts`() = runBlocking {
        lateinit var parentContext: CommandContext
        val completionStarted = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        val scope = object : CommandExecutionScope {
            override fun begin(context: CommandContext) = Unit
            override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                completionStarted.complete(Unit)
                releaseCompletion.await()
                return null
            }
        }
        val commandPipeline = pipeline(TestHandler { context ->
            parentContext = context
            null
        }, scopes = listOf(scope))
        val outer = async { commandPipeline.execute(TestCommand("outer"), options) }
        completionStarted.await()

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                commandPipeline.execute(TestCommand("late-child"), CommandExecutionOptions.nested(parentContext))
            }
        }
        releaseCompletion.complete(Unit)

        assertEquals("The supplied command execution token is closed.", exception.message)
        assertTrue(outer.await().isSuccess)
    }

    @Test
    fun `ignored nested failure poisons outer success`() = runBlocking {
        lateinit var commandPipeline: DefaultCommandPipeline
        val outerScope = RecordingScope(mutableListOf(), "scope")
        val handler = TestHandler { context ->
            if ((context.command as TestCommand).value == "outer") {
                val ignored = commandPipeline.execute(TestCommand("child"), options)
                assertFalse(ignored.isSuccess)
                "must be cleared"
            } else {
                throw IllegalStateException("child failed")
            }
        }
        commandPipeline = pipeline(handler, scopes = listOf(outerScope))

        val result = commandPipeline.execute(TestCommand("outer"), options)

        assertFalse(result.isSuccess)
        assertEquals(
            listOf("A nested command execution failed; the root execution is rollback-only."),
            result.exceptionMessages
        )
        assertNull(result.response)
        assertFalse(outerScope.resultsAtCompletion.last().isSuccess)
    }

    @Test
    fun `ignored nested cancellation poisons outer success and child cleanup sees failure`() = runBlocking {
        lateinit var commandPipeline: DefaultCommandPipeline
        val scope = RecordingScope(mutableListOf(), "scope")
        val handler = TestHandler { context ->
            if ((context.command as TestCommand).value == "outer") {
                try {
                    commandPipeline.execute(TestCommand("child"), options)
                } catch (_: CancellationException) {
                    // Deliberately ignored to prove ownership remains rollback-only.
                }
                null
            } else {
                throw CancellationException("child cancelled")
            }
        }
        commandPipeline = pipeline(handler, scopes = listOf(scope))

        val result = commandPipeline.execute(TestCommand("outer"), options)

        assertFalse(result.isSuccess)
        assertEquals(2, scope.resultsAtCompletion.size)
        assertTrue(scope.resultsAtCompletion.all { !it.isSuccess })
    }

    @Test
    fun `closed supplied token is rejected before scopes begin`() {
        lateinit var parentContext: CommandContext
        val scope = RecordingScope(mutableListOf(), "scope")
        val commandPipeline = pipeline(TestHandler { context ->
            parentContext = context
            null
        }, scopes = listOf(scope))
        runBlocking { commandPipeline.execute(TestCommand("parent"), options) }
        val nested = CommandExecutionOptions.nested(parentContext)

        val exception = assertThrows(IllegalStateException::class.java) {
            runBlocking { commandPipeline.execute(TestCommand("late child"), nested) }
        }

        assertEquals("The supplied command execution token is closed.", exception.message)
        assertEquals(1, scope.beginCount)
    }

    @Test
    fun `correlation and tenant namespace mismatches reject a nested join`() = runBlocking {
        lateinit var commandPipeline: DefaultCommandPipeline
        val handler = TestHandler { context ->
            if ((context.command as TestCommand).value == "outer") {
                val token = requireNotNull(context.executionToken)
                val wrongCorrelation = CommandExecutionOptions(
                    UUID.randomUUID(), principal, services, tenantNamespace = context.tenantNamespace
                ).join(token)
                val wrongNamespace = CommandExecutionOptions(
                    context.correlationId, principal, services, tenantNamespace = "other"
                ).join(token)
                val correlationMessage = try {
                    commandPipeline.execute(TestCommand("correlation"), wrongCorrelation)
                    null
                } catch (exception: IllegalArgumentException) {
                    exception.message
                }
                val namespaceMessage = try {
                    commandPipeline.execute(TestCommand("namespace"), wrongNamespace)
                    null
                } catch (exception: IllegalArgumentException) {
                    exception.message
                }
                assertEquals(
                    "A nested command execution must use the root correlation identifier.",
                    correlationMessage
                )
                assertEquals(
                    "A nested command execution must use the root tenant namespace.",
                    namespaceMessage
                )
            }
            null
        }
        commandPipeline = pipeline(handler)

        val result = commandPipeline.execute(TestCommand("outer"), options)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `completion failure monotonically poisons scopes completed later`() = runBlocking {
        var earlierSawSuccess: Boolean? = null
        val earlier = object : CommandExecutionScope {
            override fun begin(context: CommandContext) = Unit
            override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                earlierSawSuccess = result.isSuccess
                return null
            }
        }
        val later = RecordingScope(
            mutableListOf(),
            "later",
            completionFailure = IllegalStateException("completion failed")
        )

        val result = pipeline(TestHandler(), scopes = listOf(earlier, later)).execute(TestCommand("one"), options)

        assertFalse(result.isSuccess)
        assertEquals(false, earlierSawSuccess)
    }

    @Test
    fun `missing handler begins no execution scope`() = runBlocking {
        val scope = RecordingScope(mutableListOf(), "scope")

        val result = pipeline(scopes = listOf(scope)).execute(TestCommand("missing"), options)

        assertFalse(result.isSuccess)
        assertEquals(0, scope.beginCount)
        assertEquals(0, scope.completeCount)
    }

    @Test
    fun `registry rejects duplicates and snapshots by command type name`() {
        val registry = ConcurrentCommandHandlerRegistry()
        val zHandler = HandlerFor(ZCommand::class.java)
        val aHandler = HandlerFor(ACommand::class.java)
        registry.register(zHandler)
        registry.register(aHandler)

        assertEquals(listOf(ACommand::class.java, ZCommand::class.java), registry.snapshot().map { it.commandType })
        assertSame(aHandler, registry.find(ACommand::class.java))
        val exception = assertThrows(DuplicateCommandHandlerException::class.java) { registry.register(aHandler) }
        assertEquals(ACommand::class.java, exception.commandType)
    }

    private fun pipeline(
        handler: CommandHandler? = null,
        filters: List<CommandFilter> = emptyList(),
        scopes: List<CommandExecutionScope> = emptyList(),
        responseHandlers: List<CommandResponseValueHandler> = emptyList(),
        contextValuesProviders: List<CommandContextValuesProvider> = emptyList()
    ): DefaultCommandPipeline {
        val registry = ConcurrentCommandHandlerRegistry()
        if (handler != null) registry.register(handler)
        return DefaultCommandPipeline(registry, filters, scopes, responseHandlers, contextValuesProviders)
    }

    private fun error(message: String): ValidationResult =
        ValidationResult(ValidationResultSeverity.Error, message)

    private fun responseDescriptor(
        typeName: String,
        isEnumerable: Boolean,
        disposition: CommandResponseValueDisposition
    ): CommandResponseValueDescriptor = CommandResponseValueDescriptor(typeName, isEnumerable, disposition)

    private suspend fun executeMetadataResponse(
        response: Any,
        descriptor: CommandResponseValueDescriptor
    ): CommandResult<*> = pipeline(TestHandler(listOf(descriptor)) { response })
        .execute(TestCommand("metadata response"), options)

    private fun namedFilter(name: String, order: MutableList<String>): CommandFilter = CommandFilter {
        order.add(name)
        CommandResult.success(it.correlationId)
    }

    private fun namedAuthorizationFilter(name: String, order: MutableList<String>): AuthorizationCommandFilter =
        object : AuthorizationCommandFilter {
            override suspend fun execute(context: CommandContext): CommandResult<*> {
                order.add(name)
                return CommandResult.success(context.correlationId)
            }
        }

    private fun respondingControlHandler(response: String): CommandResponseValueHandler =
        object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean = value === ControlValue

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
                CommandResult.success(context.correlationId, response)
        }

    private fun controlHandler(
        name: String,
        calls: MutableList<String>,
        severity: ValidationResultSeverity
    ): CommandResponseValueHandler = object : CommandResponseValueHandler {
        override fun canHandle(context: CommandContext, value: Any): Boolean = value === ControlValue

        override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
            calls.add(name)
            return CommandResult.invalid(context.correlationId, listOf(ValidationResult(severity, name)))
        }
    }

    private data class TestCommand(val value: String)
    private class ACommand
    private class ZCommand
    private data class OrderedControl(val name: String)
    private data class Client(val value: String)
    private data object ControlValue
    private data object CustomHandledValue
    private data object StaticallyHandled

    @HandlesCommandResponseValues(StaticallyHandled::class)
    private class AnnotatedStaticallyHandledResponseHandler : CommandResponseValueHandler {
        var invocationCount: Int = 0
            private set

        override fun canHandle(context: CommandContext, value: Any): Boolean = value === StaticallyHandled

        override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
            invocationCount++
            return CommandResult.success(context.correlationId)
        }
    }

    @HandlesCommandResponseValues(CustomHandledValue::class)
    private class ScalarCustomResponseHandler : CommandResponseValueHandler {
        override fun canHandle(context: CommandContext, value: Any): Boolean = value === CustomHandledValue

        override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
            CommandResult.success(context.correlationId)
    }

    private open class TestHandler(
        responseValues: List<CommandResponseValueDescriptor> = emptyList(),
        private val operation: suspend (CommandContext) -> Any? = { null }
    ) : CommandHandler {
        override val commandType: Class<*> = TestCommand::class.java
        override val metadata: CommandDescriptor = CommandDescriptor(
            "TestCommand",
            commandType.name,
            responseValues = responseValues
        )
        override val allowsAnonymous: Boolean = false
        var invocationCount: Int = 0
            private set

        override suspend fun invoke(context: CommandContext): Any? {
            invocationCount++
            return operation(context)
        }
    }

    private class HandlerFor(override val commandType: Class<*>) : CommandHandler {
        override val metadata: CommandDescriptor = CommandDescriptor(commandType.simpleName, commandType.name)
        override val allowsAnonymous: Boolean = true
        override suspend fun invoke(context: CommandContext): Any? = null
    }

    private class RecordingScope(
        private val order: MutableList<String>,
        private val name: String,
        private val beginFailure: Exception? = null,
        private val completionFailure: Exception? = null,
        private val completionResult: CommandResult<*>? = null
    ) : CommandExecutionScope {
        var beginCount: Int = 0
            private set
        var completeCount: Int = 0
            private set
        var contextAtBegin: CommandContext? = null
            private set
        var contextAtComplete: CommandContext? = null
            private set
        val resultsAtCompletion: MutableList<CommandResult<*>> = mutableListOf()

        override fun begin(context: CommandContext) {
            beginCount++
            contextAtBegin = context
            order.add("begin:$name")
            beginFailure?.let { throw it }
        }

        override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
            completeCount++
            contextAtComplete = context
            resultsAtCompletion.add(result)
            order.add("complete:$name")
            completionFailure?.let { throw it }
            return completionResult
        }
    }

    private class MapServiceResolver : ServiceResolver {
        private val values = mutableMapOf<Class<*>, Any>()

        override fun <T : Any> resolve(type: Class<T>): T? = type.cast(values[type])
    }
}
