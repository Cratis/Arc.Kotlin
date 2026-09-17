// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ApiEndpointOptions
import java.io.File
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class WriteArcEndpointOptionsResourceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `writes resource with default options`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val outputDir = temporaryDirectory.resolve("output").toFile()

        val task = project.tasks.register("writeArcEndpointOptionsResource", WriteArcEndpointOptionsResource::class.java) { t ->
            t.routePrefix.set("api")
            t.segmentsToSkipForRoute.set(0)
            t.includeCommandNameInRoute.set(true)
            t.includeQueryNameInRoute.set(true)
            t.enableQueryHttpMethod.set(true)
            t.outputDirectory.set(outputDir)
        }.get()

        task.write()

        val resourceFile = File(outputDir, "META-INF/arc/endpoint-options.json")
        assertTrue(resourceFile.exists(), "resource file must exist at META-INF/arc/endpoint-options.json")

        val parsed = parseOptions(resourceFile.readText())
        assertEquals(ApiEndpointOptions(), parsed)
    }

    @Test
    fun `writes resource with non-default options`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val outputDir = temporaryDirectory.resolve("non-default-output").toFile()

        val task = project.tasks.register("writeArcEndpointOptionsResource2", WriteArcEndpointOptionsResource::class.java) { t ->
            t.routePrefix.set("my-api")
            t.segmentsToSkipForRoute.set(6)
            t.includeCommandNameInRoute.set(false)
            t.includeQueryNameInRoute.set(true)
            t.enableQueryHttpMethod.set(false)
            t.outputDirectory.set(outputDir)
        }.get()

        task.write()

        val resourceFile = File(outputDir, "META-INF/arc/endpoint-options.json")
        assertTrue(resourceFile.exists(), "resource file must exist at META-INF/arc/endpoint-options.json")

        val parsed = parseOptions(resourceFile.readText())
        assertEquals(
            ApiEndpointOptions(
                routePrefix = "my-api",
                segmentsToSkipForRoute = 6,
                includeCommandNameInRoute = false,
                includeQueryNameInRoute = true,
                enableQueryHttpMethod = false
            ),
            parsed
        )
    }

    @Test
    fun `written resource carries version 1`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val outputDir = temporaryDirectory.resolve("version-output").toFile()

        val task = project.tasks.register("writeArcEndpointOptionsResource3", WriteArcEndpointOptionsResource::class.java) { t ->
            t.routePrefix.set("api")
            t.segmentsToSkipForRoute.set(0)
            t.includeCommandNameInRoute.set(true)
            t.includeQueryNameInRoute.set(true)
            t.enableQueryHttpMethod.set(true)
            t.outputDirectory.set(outputDir)
        }.get()

        task.write()

        val content = File(outputDir, "META-INF/arc/endpoint-options.json").readText()
        val node = ArcObjectMapper.create().readTree(content)
        assertEquals(1, node.path("version").asInt(-1))
    }

    private fun parseOptions(json: String): ApiEndpointOptions {
        val node = ArcObjectMapper.create().readTree(json)
        return ApiEndpointOptions(
            routePrefix = node.path("routePrefix").stringValue(),
            segmentsToSkipForRoute = node.path("segmentsToSkipForRoute").asInt(),
            includeCommandNameInRoute = node.path("includeCommandNameInRoute").asBoolean(),
            includeQueryNameInRoute = node.path("includeQueryNameInRoute").asBoolean(),
            enableQueryHttpMethod = node.path("enableQueryHttpMethod").asBoolean()
        )
    }
}
