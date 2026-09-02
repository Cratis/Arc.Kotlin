// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.await
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.ValidationResult
import java.util.concurrent.CompletionStage

/** Synchronous Java implementation surface for command validation. */
public interface BlockingCommandValidator<T : Any> {
    /** Exact command type accepted by this validator. */
    public val commandType: Class<T>

    /** Validates a command without coroutine types in the signature. */
    public fun validate(command: T, context: CommandContext): List<ValidationResult>
}

/** CompletionStage-based Java implementation surface for command validation. */
public interface AsyncCommandValidator<T : Any> {
    /** Exact command type accepted by this validator. */
    public val commandType: Class<T>

    /** Validates a command asynchronously. */
    public fun validate(command: T, context: CommandContext): CompletionStage<List<ValidationResult>>
}

/** Adapts a [BlockingCommandValidator] to Arc's suspending command validator SPI. */
public class BlockingCommandValidatorAdapter<T : Any>(private val validator: BlockingCommandValidator<T>) : CommandValidator<T> {
    override val commandType: Class<T> get() = validator.commandType
    override suspend fun validate(command: T, context: CommandContext): List<ValidationResult> = validator.validate(command, context)
}

/** Adapts an [AsyncCommandValidator] to Arc's suspending command validator SPI. */
public class AsyncCommandValidatorAdapter<T : Any>(private val validator: AsyncCommandValidator<T>) : CommandValidator<T> {
    override val commandType: Class<T> get() = validator.commandType
    override suspend fun validate(command: T, context: CommandContext): List<ValidationResult> = validator.validate(command, context).await()
}

/** Synchronous Java implementation surface for query validation. */
public interface BlockingQueryValidator {
    /** Query matched by this validator; null matches every query. */
    public val queryName: FullyQualifiedQueryName?

    /** Validates a query without coroutine types in the signature. */
    public fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult>
}

/** CompletionStage-based Java implementation surface for query validation. */
public interface AsyncQueryValidator {
    /** Query matched by this validator; null matches every query. */
    public val queryName: FullyQualifiedQueryName?

    /** Validates a query asynchronously. */
    public fun validate(request: QueryRequest, context: QueryContext): CompletionStage<List<ValidationResult>>
}

/** Adapts a [BlockingQueryValidator] to Arc's suspending query validator SPI. */
public class BlockingQueryValidatorAdapter(private val validator: BlockingQueryValidator) : QueryValidator {
    override val queryName: FullyQualifiedQueryName? get() = validator.queryName
    override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> =
        validator.validate(request, context)
}

/** Adapts an [AsyncQueryValidator] to Arc's suspending query validator SPI. */
public class AsyncQueryValidatorAdapter(private val validator: AsyncQueryValidator) : QueryValidator {
    override val queryName: FullyQualifiedQueryName? get() = validator.queryName
    override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> =
        validator.validate(request, context).await()
}
