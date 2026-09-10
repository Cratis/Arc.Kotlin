// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
/** Framework-neutral assertions over a real Arc [CommandResult]. */
public class CommandScenarioResult<TResponse>(public val result: CommandResult<TResponse>) {
    /** Asserts that the command succeeded. */
    public fun shouldSucceed(): CommandScenarioResult<TResponse> = assertThat(
        result.isSuccess,
        "Expected the command to succeed."
    )

    /** Asserts that the command did not succeed. */
    public fun shouldFail(): CommandScenarioResult<TResponse> = assertThat(
        !result.isSuccess,
        "Expected the command to fail."
    )

    /** Asserts that authorization accepted the command, regardless of any later rejection. */
    public fun shouldBeAuthorized(): CommandScenarioResult<TResponse> = assertThat(
        result.isAuthorized,
        "Expected the command to be authorized."
    )

    /** Asserts that authorization rejected the command. */
    public fun shouldBeUnauthorized(): CommandScenarioResult<TResponse> = assertThat(
        !result.isAuthorized,
        "Expected the command to be unauthorized."
    )

    /** Asserts that the command produced no validation feedback of any severity. */
    public fun shouldBeValid(): CommandScenarioResult<TResponse> = assertThat(
        result.isValid,
        "Expected the command to be valid."
    )

    /**
     * Asserts that validation rejected the command because validation actually ran.
     *
     * A result containing only `dependencyUnavailable` feedback means required test infrastructure
     * was not supplied and no validation rule ran. Assert that case explicitly with
     * [shouldHaveValidation] and [ValidationResultReasons.DEPENDENCY_UNAVAILABLE].
     */
    public fun shouldBeInvalid(): CommandScenarioResult<TResponse> {
        assertThat(!result.isValid, "Expected the command to be invalid.")
        if (result.validationResults.all { it.reason == ValidationResultReasons.DEPENDENCY_UNAVAILABLE }) {
            fail(
                "Expected validation to reject the command, but only dependencyUnavailable feedback was produced; " +
                    "register the missing test dependency or assert that reason explicitly with shouldHaveValidation."
            )
        }
        return this
    }

    /**
     * Finds validation feedback matching every non-null criterion.
     *
     * [message] is matched as a case-sensitive fragment; [member] and [reason] are exact.
     */
    @JvmOverloads
    public fun shouldHaveValidation(
        member: String? = null,
        message: String? = null,
        reason: String? = null
    ): ValidationResult = result.validationResults.firstOrNull { validation ->
        (member == null || member in validation.members) &&
            (message == null || validation.message.contains(message)) &&
            (reason == null || validation.reason == reason)
    } ?: fail(
        "Expected validation matching member=${quoted(member)}, message=${quoted(message)}, reason=${quoted(reason)}."
    )

    /** Asserts that at least one exception message was retained. */
    public fun shouldHaveErrors(): CommandScenarioResult<TResponse> = assertThat(
        result.hasExceptions,
        "Expected the command to have errors."
    )

    /** Asserts that no exception message was retained. */
    public fun shouldHaveNoErrors(): CommandScenarioResult<TResponse> = assertThat(
        !result.hasExceptions,
        "Expected the command to have no errors."
    )

    /** Asserts that an exception message contains [message]. */
    public fun shouldHaveExceptionMessage(message: String): CommandScenarioResult<TResponse> = assertThat(
        result.exceptionMessages.any { it.contains(message) },
        "Expected an exception message containing '$message'."
    )

    /** Asserts and returns a response assignable to [responseType]. */
    public fun <T : Any> shouldHaveResponse(responseType: Class<T>): T {
        val response = result.response
        if (response == null || !responseType.isInstance(response)) {
            fail("Expected a response of type '${responseType.name}'.")
        }
        return responseType.cast(response)
    }

    /** Asserts that the command has no response. */
    public fun shouldHaveNoResponse(): CommandScenarioResult<TResponse> = assertThat(
        result.response == null,
        "Expected the command to have no response."
    )

    /** Readable complete outcome used by assertion failures. */
    public fun summary(): String = buildString {
        append("CommandResult(correlationId=${result.correlationId}, success=${result.isSuccess}, ")
        append("authorized=${result.isAuthorized}, valid=${result.isValid}, exceptions=${result.hasExceptions})")
        if (result.authorizationFailureReason.isNotEmpty()) {
            append("\nAuthorization failure: ${result.authorizationFailureReason}")
        }
        if (result.validationResults.isNotEmpty()) {
            append("\nValidation:")
            result.validationResults.forEach { validation ->
                append("\n  [${validation.severity}/${validation.reason}] ${validation.message}")
                if (validation.members.isNotEmpty()) append(" (members: ${validation.members.joinToString()})")
            }
        }
        if (result.exceptionMessages.isNotEmpty()) {
            append("\nExceptions:")
            result.exceptionMessages.forEach { append("\n  $it") }
        }
        result.response?.let { append("\nResponse: ${it.javaClass.name} = $it") }
    }

    override fun toString(): String = summary()

    private fun assertThat(condition: Boolean, expectation: String): CommandScenarioResult<TResponse> {
        if (!condition) fail(expectation)
        return this
    }

    private fun fail(expectation: String): Nothing = throw AssertionError("$expectation\n${summary()}")

    private fun quoted(value: String?): String = value?.let { "'$it'" } ?: "<any>"
}
