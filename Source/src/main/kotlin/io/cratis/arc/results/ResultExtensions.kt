// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

/**
 * Applies [onSuccess] to the optional command response when this result succeeded, or [onFailure] to the complete
 * result envelope otherwise.
 */
@JvmSynthetic
public inline fun <T, R> CommandResult<T>.fold(
    onSuccess: (T?) -> R,
    onFailure: (CommandResult<T>) -> R
): R = if (isSuccess) onSuccess(response) else onFailure(this)

/** Returns the optional command response, or throws [IllegalStateException] when this result did not succeed. */
@JvmSynthetic
public fun <T> CommandResult<T>.getOrThrow(): T? {
    check(isSuccess) { failureDescription() }
    return response
}

/** Invokes [action] with the optional command response when this result succeeded and returns this result unchanged. */
@JvmSynthetic
public inline fun <T> CommandResult<T>.onSuccess(action: (T?) -> Unit): CommandResult<T> = apply {
    if (isSuccess) action(response)
}

/** Returns non-empty validation feedback, or `null` when this result has no validation feedback. */
@JvmSynthetic
public fun CommandResult<*>.validationOrNull(): List<ValidationResult>? = validationResults.takeIf { it.isNotEmpty() }

/**
 * Applies [onSuccess] to the optional query data when this result succeeded, or [onFailure] to the complete result
 * envelope otherwise.
 */
@JvmSynthetic
public inline fun <TData, R> QueryResult<TData>.fold(
    onSuccess: (TData?) -> R,
    onFailure: (QueryResult<TData>) -> R
): R = if (isSuccess) onSuccess(data) else onFailure(this)

/** Returns the optional query data, or throws [IllegalStateException] when this result did not succeed. */
@JvmSynthetic
public fun <TData> QueryResult<TData>.getOrThrow(): TData? {
    check(isSuccess) { failureDescription() }
    return data
}

/** Invokes [action] with the optional query data when this result succeeded and returns this result unchanged. */
@JvmSynthetic
public inline fun <TData> QueryResult<TData>.onSuccess(action: (TData?) -> Unit): QueryResult<TData> = apply {
    if (isSuccess) action(data)
}

/** Returns non-empty validation feedback, or `null` when this result has no validation feedback. */
@JvmSynthetic
public fun QueryResult<*>.validationOrNull(): List<ValidationResult>? = validationResults.takeIf { it.isNotEmpty() }

private fun CommandResult<*>.failureDescription(): String = when {
    !isAuthorized && authorizationFailureReason.isNotBlank() -> authorizationFailureReason
    !isAuthorized -> "Command was not authorized."
    validationResults.isNotEmpty() -> validationResults.joinToString("; ", transform = ValidationResult::message)
    exceptionMessages.isNotEmpty() -> exceptionMessages.joinToString("; ")
    else -> "Command did not succeed."
}

private fun QueryResult<*>.failureDescription(): String = when {
    !isReady -> "Query data is not ready."
    !isAuthorized -> "Query was not authorized."
    validationResults.isNotEmpty() -> validationResults.joinToString("; ", transform = ValidationResult::message)
    exceptionMessages.isNotEmpty() -> exceptionMessages.joinToString("; ")
    else -> "Query did not succeed."
}
