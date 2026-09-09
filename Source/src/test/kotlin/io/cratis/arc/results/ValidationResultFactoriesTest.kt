// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ValidationResultFactoriesTest {
    @Test
    fun `severity factories set their severity and default the remaining values`() {
        val information = ValidationResult.information("Nothing to do.")
        val warning = ValidationResult.warning("Close to the limit.")
        val error = ValidationResult.error("A task title is required.")

        assertEquals(ValidationResultSeverity.Information, information.severity)
        assertEquals(ValidationResultSeverity.Warning, warning.severity)
        assertEquals(ValidationResultSeverity.Error, error.severity)
        listOf(information, warning, error).forEach { result ->
            assertTrue(result.members.isEmpty())
            assertNull(result.state)
            assertEquals(ValidationResultReasons.RULE, result.reason)
            assertNull(result.reasonDetail)
        }
        assertEquals("A task title is required.", error.message)
    }

    @Test
    fun `severity factories carry members, state, reason, and reason detail`() {
        val state = Any()

        val result = ValidationResult.error(
            "The read model could not be resolved.",
            listOf("title"),
            state,
            ValidationResultReasons.DEPENDENCY_UNAVAILABLE,
            "commandKey"
        )

        assertEquals(ValidationResultSeverity.Error, result.severity)
        assertEquals(listOf("title"), result.members)
        assertSame(state, result.state)
        assertEquals(ValidationResultReasons.DEPENDENCY_UNAVAILABLE, result.reason)
        assertEquals("commandKey", result.reasonDetail)
    }

    @Test
    fun `factory members are copied defensively like the constructor`() {
        val members = mutableListOf("title")

        val result = ValidationResult.warning("A task title is unusual.", members)
        members.add("description")

        assertEquals(listOf("title"), result.members)
    }
}
