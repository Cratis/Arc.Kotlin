// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ChangeSetComputerTest {
    private val computer = ChangeSetComputer()

    @Test
    fun `keyless values use serialized identity for additions and removals`() {
        val retained = Keyless("retained")
        val added = Keyless("added")
        val removed = Keyless("removed")

        val changeSet = computer.compute(listOf(retained, removed), listOf(retained, added))

        assertEquals(listOf(added), changeSet.added)
        assertEquals(listOf(removed), changeSet.removed)
        assertTrue(changeSet.replaced.isEmpty())
    }

    @Test
    fun `keyless field changes are a removal and addition rather than a replacement`() {
        val before = Keyless("before")
        val after = Keyless("after")

        val changeSet = computer.compute(listOf(before), listOf(after))

        assertEquals(listOf(after), changeSet.added)
        assertEquals(listOf(before), changeSet.removed)
        assertTrue(changeSet.replaced.isEmpty())
    }

    @Test
    fun `missing and duplicate extracted keys fall back to serialized identity`() {
        val previous = listOf(Keyless("one"), Keyless("two"))
        val current = listOf(Keyless("two"), Keyless("three"))

        for (extractor in listOf<(Any) -> Any?>({ null }, { "same" })) {
            val changeSet = computer.compute(previous, current, extractor)
            assertEquals(listOf(Keyless("three")), changeSet.added)
            assertEquals(listOf(Keyless("one")), changeSet.removed)
            assertTrue(changeSet.replaced.isEmpty())
        }
    }

    @Test
    fun `serialized identity keeps duplicate occurrence behavior aligned with dotnet`() {
        val duplicate = Keyless("same")

        assertTrue(computer.compute(listOf(duplicate), listOf(duplicate, duplicate)).added.isEmpty())
        assertTrue(computer.compute(listOf(duplicate, duplicate), listOf(duplicate)).removed.isEmpty())
    }

    private data class Keyless(val value: String)
}
