// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ResultExtensionsTest {
    private val correlationId = UUID.fromString("aa348df3-7118-4742-a313-83b4033b8a45")

    @Test
    fun `command result extensions expose successful responses and preserve the envelope`() {
        val result = CommandResult.success(correlationId, "created")
        var observed: String? = null

        val folded = result.fold(onSuccess = { it }, onFailure = { "failed" })
        val returned = result.onSuccess { observed = it }

        assertEquals("created", folded)
        assertEquals("created", result.getOrThrow())
        assertEquals("created", observed)
        assertSame(result, returned)
        assertNull(result.validationOrNull())
    }

    @Test
    fun `command result extensions retain failure details`() {
        val validation = ValidationResult(ValidationResultSeverity.Error, "Name is required.")
        val result = CommandResult.invalid(correlationId, listOf(validation))
        var called = false

        val folded = result.fold(onSuccess = { "success" }, onFailure = { it.validationResults.single().message })
        result.onSuccess { called = true }
        val exception = assertThrows(IllegalStateException::class.java) { result.getOrThrow() }

        assertEquals("Name is required.", folded)
        assertEquals(listOf(validation), result.validationOrNull())
        assertTrue(exception.message!!.contains("Name is required."))
        assertEquals(false, called)
    }

    @Test
    fun `query result extensions expose successful data and preserve the envelope`() {
        val result = QueryResult.success(correlationId, listOf("one", "two"))
        var observed: List<String>? = null

        val folded = result.fold(onSuccess = { it?.size }, onFailure = { -1 })
        val returned = result.onSuccess { observed = it }

        assertEquals(2, folded)
        assertEquals(listOf("one", "two"), result.getOrThrow())
        assertEquals(listOf("one", "two"), observed)
        assertSame(result, returned)
        assertNull(result.validationOrNull())
    }

    @Test
    fun `query getOrThrow rejects not ready results`() {
        val result = QueryResult.notReady<String>(correlationId)

        val exception = assertThrows(IllegalStateException::class.java) { result.getOrThrow() }

        assertTrue(exception.message!!.contains("not ready"))
    }
}
