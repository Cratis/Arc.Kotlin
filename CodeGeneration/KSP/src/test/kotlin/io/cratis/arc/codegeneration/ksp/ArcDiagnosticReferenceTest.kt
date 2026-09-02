// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ArcDiagnosticReferenceTest {
    @Test
    fun `diagnostic reference is generated from the stable catalog`() {
        val reference = Path.of(System.getProperty("arc.ksp.projectDir"), "DIAGNOSTICS.md")
        assertEquals(ArcDiagnostic.referenceMarkdown(), Files.readString(reference))
    }
}
