// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.results.ChangeSet
import io.cratis.arc.results.PagingInfo
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult

/** Framework-neutral assertions over a real Arc [QueryResult]. */
public class QueryScenarioResult<TData>(public val result: QueryResult<TData>) {
    /** Asserts that data is ready. */
    public fun shouldBeReady(): QueryScenarioResult<TData> = assertThat(result.isReady, "Expected query data to be ready.")

    /** Asserts that the query succeeded. */
    public fun shouldSucceed(): QueryScenarioResult<TData> = assertThat(result.isSuccess, "Expected the query to succeed.")

    /** Asserts that authorization rejected the query. */
    public fun shouldBeUnauthorized(): QueryScenarioResult<TData> = assertThat(
        !result.isAuthorized,
        "Expected the query to be unauthorized."
    )

    /** Asserts that validation rejected the query. */
    public fun shouldBeInvalid(): QueryScenarioResult<TData> = assertThat(!result.isValid, "Expected the query to be invalid.")

    /** Asserts that data equals [expected]. */
    public fun shouldHaveData(expected: TData?): QueryScenarioResult<TData> = assertThat(
        result.data == expected,
        "Expected query data to equal '$expected'."
    )

    /** Asserts and returns data assignable to [dataType]. */
    public fun <T : Any> shouldHaveData(dataType: Class<T>): T {
        val data = result.data
        if (data == null || !dataType.isInstance(data)) fail("Expected query data of type '${dataType.name}'.")
        return dataType.cast(data)
    }

    /** Asserts response paging values. */
    public fun shouldHavePaging(page: Int, size: Int, totalItems: Long): PagingInfo {
        val paging = result.paging
        if (paging.page != page || paging.size != size || paging.totalItems != totalItems) {
            fail("Expected paging page=$page, size=$size, totalItems=$totalItems.")
        }
        return paging
    }

    /** Asserts and returns an incremental change set. */
    public fun shouldHaveChangeSet(): ChangeSet<*> = result.changeSet ?: fail("Expected the query to have a change set.")

    /** Asserts that the query has no incremental change set. */
    public fun shouldHaveNoChangeSet(): QueryScenarioResult<TData> = assertThat(
        result.changeSet == null,
        "Expected the query to have no change set."
    )

    /** Asserts that at least one exception error was retained. */
    public fun shouldHaveErrors(): QueryScenarioResult<TData> = assertThat(
        result.exceptionMessages.isNotEmpty(),
        "Expected the query to have errors."
    )

    /** Asserts that no exception errors were retained. */
    public fun shouldHaveNoErrors(): QueryScenarioResult<TData> = assertThat(
        result.exceptionMessages.isEmpty(),
        "Expected the query to have no errors."
    )

    /** Asserts that an exception message contains [message]. */
    public fun shouldHaveExceptionMessage(message: String): QueryScenarioResult<TData> = assertThat(
        result.exceptionMessages.any { it.contains(message) },
        "Expected an exception message containing '$message'."
    )

    /** Finds validation feedback matching every non-null criterion. */
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

    /** Readable complete outcome used by assertion failures. */
    public fun summary(): String = buildString {
        append("QueryResult(correlationId=${result.correlationId}, ready=${result.isReady}, success=${result.isSuccess}, ")
        append("authorized=${result.isAuthorized}, valid=${result.isValid}, exceptions=${result.hasExceptions})")
        append("\nPaging: page=${result.paging.page}, size=${result.paging.size}, totalItems=${result.paging.totalItems}")
        if (result.validationResults.isNotEmpty()) {
            append("\nValidation:")
            result.validationResults.forEach { validation ->
                append("\n  [${validation.severity}/${validation.reason}] ${validation.message}")
                if (validation.members.isNotEmpty()) append(" (members: ${validation.members.joinToString()})")
            }
        }
        if (result.exceptionMessages.isNotEmpty()) {
            append("\nErrors:")
            result.exceptionMessages.forEach { append("\n  $it") }
        }
        result.data?.let { append("\nData: ${it.javaClass.name} = $it") }
        result.changeSet?.let {
            append("\nChangeSet: added=${it.added.size}, replaced=${it.replaced.size}, removed=${it.removed.size}")
        }
    }

    override fun toString(): String = summary()

    private fun assertThat(condition: Boolean, expectation: String): QueryScenarioResult<TData> {
        if (!condition) fail(expectation)
        return this
    }

    private fun fail(expectation: String): Nothing = throw AssertionError("$expectation\n${summary()}")

    private fun quoted(value: String?): String = value?.let { "'$it'" } ?: "<any>"
}
