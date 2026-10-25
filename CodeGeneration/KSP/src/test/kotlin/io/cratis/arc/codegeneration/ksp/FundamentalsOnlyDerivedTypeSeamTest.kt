// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pins the KSP copy of the Q-79 seam; reverting Q-79 is a one-line change in MetadataConstants plus this test. */
internal class FundamentalsOnlyDerivedTypeSeamTest {
    @Test
    fun `Q-79 fundamentals only derived types are honoured`() {
        assertTrue(honoursFundamentalsOnlyDerivedTypes())
        assertEquals(
            listOf(LEGACY_DERIVED_TYPE_ANNOTATION, DERIVED_TYPE_ANNOTATION),
            derivedTypeAnnotations()
        )
    }
}
