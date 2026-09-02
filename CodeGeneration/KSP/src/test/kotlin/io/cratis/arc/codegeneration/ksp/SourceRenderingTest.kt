// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SourceRenderingTest {
    @Test
    fun `quoted metadata is valid deterministic Kotlin text`() {
        assertEquals("\"line\\n\\\"quoted\\\"\\\\path\"", quote("line\n\"quoted\"\\path"))
    }
}
