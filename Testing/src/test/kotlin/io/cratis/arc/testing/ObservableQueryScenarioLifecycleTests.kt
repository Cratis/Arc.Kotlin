// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryEmissionVerdict
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ObservableQueryScenarioLifecycleTests {
    @Test
    fun `timeout bounds suspending authorization while opening`() = runBlocking {
        val suspension = Suspension()
        val performer = Performer(authorization = AuthorizationMetadata(policy = "wait"))
        val scenario = ObservableQueryScenario<String>(performer)
            .withPrincipal(ArcPrincipal("tester", true))
            .addPolicy("wait", AuthorizationPolicy { suspension.wait() })

        assertScenarioTimeout(scenario, suspension)
        assertEquals(0, performer.invocations)
    }

    @Test
    fun `timeout bounds suspending filter while opening`() = runBlocking {
        val suspension = Suspension()
        val performer = Performer()
        val scenario = ObservableQueryScenario<String>(performer)
            .addFilter(QueryFilter { suspension.wait() })

        assertScenarioTimeout(scenario, suspension)
        assertEquals(0, performer.invocations)
    }

    @Test
    fun `timeout bounds suspending performer while opening`() = runBlocking {
        val suspension = Suspension()
        assertScenarioTimeout(ObservableQueryScenario(Performer { suspension.wait() }), suspension)
    }

    @Test
    fun `timeout bounds collection and cleans upstream`() = runBlocking {
        val suspension = Suspension()
        assertScenarioTimeout(
            ObservableQueryScenario(Performer { flow { emit("first"); suspension.wait() } }),
            suspension
        )
    }

    @Test
    fun `caller cancellation propagates during opening and cleans suspended performer`() = runBlocking {
        val suspension = Suspension()
        assertCallerCancellation(ObservableQueryScenario(Performer { suspension.wait() }), suspension)
    }

    @Test
    fun `caller cancellation propagates during collection and cleans upstream`() = runBlocking {
        val suspension = Suspension()
        assertCallerCancellation(
            ObservableQueryScenario(Performer { flow { emit("first"); suspension.wait() } }),
            suspension
        )
    }

    @Test
    fun `performer cancellation is not converted to an opening failure`() = runBlocking {
        val cancellation = CancellationException("performer cancelled")
        val outcome = runCatching { ObservableQueryScenario<String>(Performer { throw cancellation }).collect(1) }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any { it === cancellation })
    }

    @Test
    fun `upstream cancellation is not converted to an emission`() = runBlocking {
        val cancellation = CancellationException("upstream cancelled")
        val outcome = runCatching {
            ObservableQueryScenario<String>(Performer { flow<String> { throw cancellation } }).collect(1)
        }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertTrue(generateSequence(outcome.exceptionOrNull()) { it.cause }.any { it === cancellation })
    }

    @Test
    fun `finite stream may complete before the maximum`(): Unit = runBlocking {
        val result = ObservableQueryScenario<String>(Performer()).collect(5)
            .shouldSucceed().shouldHaveEmissionCount(1).shouldHaveData(0, "value")
        assertThrows(AssertionError::class.java) { result.shouldHaveEmissionCount(5) }
    }

    @Test
    fun `empty stream succeeds but an explicit nonzero emission assertion fails`(): Unit = runBlocking {
        val result = ObservableQueryScenario<String>(Performer { emptyFlow<String>() }).collect(5)
            .shouldSucceed().shouldHaveEmissionCount(0)
        assertThrows(AssertionError::class.java) { result.shouldHaveEmissionCount(1) }
    }

    @Test
    fun `maximum emission cap stops and cleans upstream without requesting another value`() = runBlocking {
        var produced = 0
        var cleaned = false
        val performer = Performer {
            flow {
                try {
                    repeat(3) { produced++; emit("value-$produced") }
                    awaitCancellation()
                } finally {
                    cleaned = true
                }
            }
        }
        ObservableQueryScenario<String>(performer).collect(2)
            .shouldSucceed().shouldHaveEmissionCount(2).shouldHaveData(1, "value-2")
        assertEquals(2, produced)
        assertTrue(cleaned)
    }

    @Test
    fun `authorization failure returns opening failure without invoking performer`() = runBlocking {
        val performer = Performer(authorization = AuthorizationMetadata(policy = "denied"))
        val result = ObservableQueryScenario<String>(performer)
            .withPrincipal(ArcPrincipal("tester", true))
            .addPolicy("denied", AuthorizationPolicy { AuthorizationResult.failure("denied") })
            .collect(5)
        assertFalse(result.shouldFail().isAuthorized)
        result.shouldHaveEmissionCount(0)
        assertThrows(AssertionError::class.java) { result.shouldSucceed() }
        assertEquals(0, performer.invocations)
    }

    @Test
    fun `terminal denial retains unauthorized emission and cleans upstream`() = runBlocking {
        var produced = 0
        var cleaned = false
        val performer = Performer {
            flow {
                try {
                    repeat(3) { produced++; emit("value-$produced") }
                } finally {
                    cleaned = true
                }
            }
        }
        val result = ObservableQueryScenario<String>(performer)
            .addEmissionGuard(BlockingObservableQueryEmissionGuard {
                if (it.isFirstEmission) ObservableQueryEmissionVerdict.ALLOW
                else ObservableQueryEmissionVerdict.DENY_AND_TERMINATE
            })
            .collect(5)
            .shouldHaveEmissionCount(2).shouldHaveData(0, "value-1").shouldTerminateUnauthorized()
        assertThrows(AssertionError::class.java) { result.shouldSucceed() }
        assertEquals(2, produced)
        assertTrue(cleaned)
    }

    @Test
    fun `descriptor warning default is honored for manual performer`() = runBlocking {
        for (strict in listOf(false, true)) {
            val performer = Performer(strict = strict)
            val result = ObservableQueryScenario<String>(performer)
                .addValidator(validator(ValidationResultSeverity.Warning)).collect(1)
            assertValidationOutcome(result, performer, strict, ValidationResultSeverity.Warning)
        }
    }

    @Test
    fun `module warning default comes from the selected descriptor`() = runBlocking {
        val selected = Performer(strict = true)
        val other = Performer(name = FullyQualifiedQueryName("Tests.Observable.other"))
        val module = object : ArcArtifactModule(emptyList(), listOf(other, selected)) {}
        val result = ObservableQueryScenario<String>(module, selected.fullyQualifiedName)
            .addValidator(validator(ValidationResultSeverity.Warning)).collect(1)
        assertValidationOutcome(result, selected, true, ValidationResultSeverity.Warning)
        assertEquals(0, other.invocations)
    }

    @Test
    fun `default severity allows information and blocks errors with either descriptor setting`() = runBlocking {
        for (strict in listOf(false, true)) {
            for (severity in listOf(ValidationResultSeverity.Information, ValidationResultSeverity.Error)) {
                val performer = Performer(strict = strict)
                val result = ObservableQueryScenario<String>(performer).addValidator(validator(severity)).collect(1)
                assertValidationOutcome(result, performer, severity == ValidationResultSeverity.Error, severity)
            }
        }
    }

    @Test
    fun `explicit thresholds including null override either descriptor default`() = runBlocking {
        for (strict in listOf(false, true)) {
            for (allowed in listOf(null) + ValidationResultSeverity.entries) {
                for (severity in ValidationResultSeverity.entries) {
                    val performer = Performer(strict = strict)
                    val result = ObservableQueryScenario<String>(performer)
                        .withAllowedValidationSeverity(ValidationResultSeverity.Unknown)
                        .withAllowedValidationSeverity(allowed)
                        .addValidator(validator(severity)).collect(1)
                    val blocked = if (allowed == null) severity == ValidationResultSeverity.Error
                        else severity.value() > allowed.value()
                    assertValidationOutcome(result, performer, blocked, severity)
                }
            }
        }
    }

    @Test
    fun `descriptor severity also applies to validation carried by emissions`() = runBlocking {
        for (strict in listOf(false, true)) {
            val performer = object : QueryPerformer {
                override val fullyQualifiedName = FullyQualifiedQueryName("Tests.Observable.values")
                override val descriptor = Performer(strict = strict).descriptor
                override suspend fun perform(context: QueryContext): Any = flowOf(
                    QueryResult.invalid<String>(context.correlationId, listOf(
                        ValidationResult(ValidationResultSeverity.Warning, "emission warning")
                    ))
                )
            }
            val result = ObservableQueryScenario<String>(performer).collect(1).shouldHaveEmissionCount(1)
            assertEquals(!strict, result.emissions.single().isSuccess)
            assertEquals(if (strict) 1 else 0, result.emissions.single().validationResults.size)
        }
    }

    @Test
    fun `missing module performer still returns an opening failure instead of throwing during setup`() = runBlocking {
        val module = object : ArcArtifactModule(emptyList(), emptyList()) {}
        val result = ObservableQueryScenario<String>(module, FullyQualifiedQueryName("Tests.missing"))
            .collect(1).shouldHaveEmissionCount(0)
        assertFalse(result.shouldFail().isSuccess)
    }

    private suspend fun assertScenarioTimeout(scenario: ObservableQueryScenario<String>, suspension: Suspension) = coroutineScope {
        val pending = async { runCatching { scenario.collect(2, timeoutMillis = 100) } }
        val outcome = try {
            withTimeoutOrNull(2_000) { pending.await() }
        } finally {
            pending.cancelAndJoin()
        }
        assertTrue(suspension.entered.isCompleted, "The intended suspension must have been reached")
        assertTrue(suspension.cleaned.isCompleted, "Cancellation must finish upstream cleanup")
        assertNotNull(outcome, "The scenario timeout must bound opening as well as collection")
        assertTrue(outcome!!.exceptionOrNull() is TimeoutCancellationException)
    }

    private suspend fun assertCallerCancellation(scenario: ObservableQueryScenario<String>, suspension: Suspension) = coroutineScope {
        val pending = async { scenario.collect(2, timeoutMillis = 10_000) }
        try {
            withTimeout(2_000) { suspension.entered.await() }
            pending.cancel(CancellationException("caller cancelled"))
            val outcome = runCatching { pending.await() }
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertFalse(outcome.exceptionOrNull() is TimeoutCancellationException)
        } finally {
            pending.cancelAndJoin()
        }
        assertTrue(suspension.cleaned.isCompleted)
    }

    private fun assertValidationOutcome(
        result: ObservableQueryScenarioResult<String>,
        performer: Performer,
        blocked: Boolean,
        severity: ValidationResultSeverity
    ) {
        if (blocked) {
            assertEquals(listOf(severity), result.shouldFail().validationResults.map { it.severity })
            result.shouldHaveEmissionCount(0)
            assertEquals(0, performer.invocations)
        } else {
            result.shouldSucceed().shouldHaveEmissionCount(1).shouldHaveData(0, "value")
            assertEquals(1, performer.invocations)
        }
    }

    private fun validator(severity: ValidationResultSeverity): QueryValidator = object : QueryValidator {
        override val queryName: FullyQualifiedQueryName? = null
        override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> =
            listOf(ValidationResult(severity, "feedback"))
    }

    private class Suspension {
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()

        suspend fun wait(): Nothing {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                cleaned.complete(Unit)
            }
        }
    }

    private class Performer(
        strict: Boolean = false,
        authorization: AuthorizationMetadata = AuthorizationMetadata(allowAnonymous = true),
        name: FullyQualifiedQueryName = FullyQualifiedQueryName("Tests.Observable.values"),
        private val operation: suspend () -> Any = { flowOf("value") }
    ) : QueryPerformer {
        override val fullyQualifiedName = name
        override val descriptor = QueryDescriptor(
            "values", "Tests.Observable", "kotlin.String",
            fullyQualifiedName = name.value,
            authorization = authorization,
            transport = QueryTransportType.OBSERVABLE,
            treatWarningsAsErrors = strict
        )
        var invocations = 0
        override suspend fun perform(context: QueryContext): Any {
            invocations++
            return operation()
        }
    }
}
