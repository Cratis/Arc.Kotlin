// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * Immutable outcome of executing a command.
 *
 * Empty validation and exception collections, stack traces, and authorization failure reasons are deliberately
 * retained on the wire. Nullable responses are omitted by the Arc object-mapper configuration.
 */
public class CommandResult<T> @JvmOverloads constructor(
    /** Correlates this outcome with the originating request. */
    public val correlationId: UUID,
    /** Whether authorization succeeded. */
    @get:JsonProperty("isAuthorized")
    public val isAuthorized: Boolean = true,
    validationResults: List<ValidationResult> = emptyList(),
    exceptionMessages: List<String> = emptyList(),
    /** Stack trace retained for host-side logging and redaction before production serialization. */
    public val exceptionStackTrace: String = "",
    /** Reason supplied by the authorization layer, or an empty string when none was supplied. */
    public val authorizationFailureReason: String = "",
    response: T? = null
) {
    /** Validation feedback. This collection is always present in JSON, including when empty. */
    public val validationResults: List<ValidationResult> = java.util.List.copyOf(validationResults)

    /** Exception messages retained for host-side logging and redaction. This collection is always present in JSON. */
    public val exceptionMessages: List<String> = java.util.List.copyOf(exceptionMessages)

    /** True only when no validation results were produced, regardless of severity. */
    @get:JsonProperty("isValid")
    public val isValid: Boolean = this.validationResults.isEmpty()

    /** True when at least one exception message was produced. */
    @get:JsonProperty("hasExceptions")
    public val hasExceptions: Boolean = this.exceptionMessages.isNotEmpty()

    /** True when authorization and validation succeeded and no exceptions were produced. */
    @get:JsonProperty("isSuccess")
    public val isSuccess: Boolean = isAuthorized && isValid && !hasExceptions

    /** Optional command response. Failed results never retain a response. */
    public val response: T? = if (isSuccess) response else null

    public companion object {
        /** Creates a successful result without a response. */
        @JvmStatic
        public fun success(): CommandResult<Void> = success(UUID.randomUUID())

        /** Creates a successful result without a response. */
        @JvmStatic
        public fun success(correlationId: UUID): CommandResult<Void> = CommandResult(correlationId)

        /** Creates a successful result with a response. */
        @JvmStatic
        public fun <T> success(correlationId: UUID, response: T): CommandResult<T> =
            CommandResult(correlationId = correlationId, response = response)

        /** Creates an unauthorized result. */
        @JvmStatic
        @JvmOverloads
        public fun unauthorized(correlationId: UUID, reason: String? = null): CommandResult<Void> =
            CommandResult(
                correlationId = correlationId,
                isAuthorized = false,
                authorizationFailureReason = reason ?: ""
            )

        /** Creates a result rejected by validation. */
        @JvmStatic
        public fun invalid(correlationId: UUID, validationResults: List<ValidationResult>): CommandResult<Void> =
            CommandResult(correlationId = correlationId, validationResults = validationResults)

        /** Creates a safe malformed-request result without retaining parser exception details. */
        @JvmStatic
        public fun malformed(correlationId: UUID): CommandResult<Void> = invalid(
            correlationId,
            listOf(
                ValidationResult(
                    ValidationResultSeverity.Error,
                    "The request body could not be read or is not valid for this command.",
                    reason = ValidationResultReasons.MALFORMED_REQUEST
                )
            )
        )

        /** Creates a framework error when no command handler exists. */
        @JvmStatic
        public fun missingHandler(correlationId: UUID, commandName: String): CommandResult<Void> =
            error(correlationId, "No handler is registered for command '$commandName'.")

        /** Creates a failure from a message that is known to be safe to expose. */
        @JvmStatic
        public fun error(correlationId: UUID, message: String): CommandResult<Void> = CommandResult(
            correlationId = correlationId,
            exceptionMessages = listOf(message)
        )

        /** Creates an exception result retaining full detail for host-side logging and redaction. */
        @JvmStatic
        public fun exception(correlationId: UUID, exception: Throwable): CommandResult<Void> = CommandResult(
            correlationId = correlationId,
            exceptionMessages = listOf(exception.message ?: exception.javaClass.simpleName),
            exceptionStackTrace = exception.stackTraceToString()
        )
    }
}
