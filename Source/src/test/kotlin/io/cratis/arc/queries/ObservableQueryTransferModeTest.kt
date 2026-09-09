// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.json.ArcObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pins how the observable-query subscription wire contract reads `transferMode`.
 *
 * Arc .NET derives the mode from two case-insensitive string comparisons, so a value it does not know leaves
 * the subscription on the same path an omitted value takes. These tests hold Arc.Kotlin to that shape: the
 * mode is a preference about how much of the snapshot travels, never a reason to refuse a subscription.
 */
internal class ObservableQueryTransferModeTest {
    private val mapper = ArcObjectMapper.create()

    @Test
    fun `known wire values read as their mode regardless of casing`() {
        assertEquals(ObservableQueryTransferMode.DELTA, read("""{"queryName":"q","transferMode":"delta"}"""))
        assertEquals(ObservableQueryTransferMode.FULL, read("""{"queryName":"q","transferMode":"full"}"""))
        assertEquals(ObservableQueryTransferMode.DELTA, read("""{"queryName":"q","transferMode":"Delta"}"""))
        assertEquals(ObservableQueryTransferMode.FULL, read("""{"queryName":"q","transferMode":"FULL"}"""))
    }

    @Test
    fun `an unrecognized wire value reads as no expressed mode`() {
        assertNull(read("""{"queryName":"q","transferMode":"compact"}"""))
        assertNull(read("""{"queryName":"q","transferMode":"fulll"}"""))
        assertNull(read("""{"queryName":"q","transferMode":""}"""))
    }

    @Test
    fun `an omitted or null wire value reads as no expressed mode`() {
        assertNull(read("""{"queryName":"q"}"""))
        assertNull(read("""{"queryName":"q","transferMode":null}"""))
    }

    @Test
    fun `a non-string wire value is still malformed`() {
        assertThrows(Exception::class.java) { read("""{"queryName":"q","transferMode":7}""") }
        assertThrows(Exception::class.java) { read("""{"queryName":"q","transferMode":{"mode":"full"}}""") }
    }

    @Test
    fun `modes serialize back to their exact lowercase wire values`() {
        assertEquals("\"delta\"", mapper.writeValueAsString(ObservableQueryTransferMode.DELTA))
        assertEquals("\"full\"", mapper.writeValueAsString(ObservableQueryTransferMode.FULL))
    }

    private fun read(json: String): ObservableQueryTransferMode? =
        mapper.readValue(json, ObservableQuerySubscriptionRequest::class.java).transferMode
}
