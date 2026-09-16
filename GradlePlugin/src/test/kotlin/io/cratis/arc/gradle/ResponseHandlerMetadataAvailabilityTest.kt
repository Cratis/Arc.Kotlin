// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.tasks.Nested
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

internal class ResponseHandlerMetadataAvailabilityTest {
    @Test
    fun `public KSP task exposes nested task local argument providers`() {
        assertNotNull(KspAATask::class.java.getMethod("getCommandLineArgumentProviders").getAnnotation(Nested::class.java))
    }

    @Test
    fun `dependency metadata task and argument provider are available`() {
        assertNotNull(Class.forName("io.cratis.arc.gradle.ExtractArcResponseHandlerMetadata"))
        assertNotNull(Class.forName("io.cratis.arc.gradle.ArcResponseHandlerMetadataArgumentProvider"))
    }
}
