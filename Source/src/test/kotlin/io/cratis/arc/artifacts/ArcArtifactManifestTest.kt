// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.artifacts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ArcArtifactManifestTest {
    @Test
    fun `new manifests use recursive type shape metadata format`() {
        val manifest = ArcArtifactManifest("tests")

        assertEquals(5, ArcArtifactManifest.CURRENT_FORMAT_VERSION)
        assertEquals(ArcArtifactManifest.CURRENT_FORMAT_VERSION, manifest.formatVersion)
    }
}
