// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import java.io.File
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Proves that [GenerateArcProxiesCli] honours `--endpoint-options-output` and produces
 * byte-identical content to [WriteArcEndpointOptionsResource] for the same option values.
 */
internal class GenerateArcProxiesCliEndpointOptionsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    // ── helper ────────────────────────────────────────────────────────────────────────────────────

    /** Writes a minimal (command- and query-free) manifest so the CLI can discover it. */
    private fun writeMinimalManifest(moduleName: String = "TestModule"): File {
        val manifestRoot = temporaryDirectory.resolve("manifests/META-INF/cratis/arc").toFile()
        manifestRoot.mkdirs()
        ArcObjectMapper.create().writeValue(
            manifestRoot.resolve("$moduleName.json"),
            ArcArtifactManifest(moduleName)
        )
        return temporaryDirectory.resolve("manifests").toFile()
    }

    // ── flag present ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `CLI writes endpoint-options resource when flag is supplied`() {
        val manifestClasspath = writeMinimalManifest()
        val outputDir = temporaryDirectory.resolve("generated").toFile()
        val endpointOptionsDir = temporaryDirectory.resolve("endpoint-options").toFile()

        GenerateArcProxiesCli.main(
            arrayOf(
                "--manifest-classpath", manifestClasspath.absolutePath,
                "--output-directory", outputDir.absolutePath,
                "--endpoint-options-output", endpointOptionsDir.absolutePath
            )
        )

        val resource = File(endpointOptionsDir, "META-INF/arc/endpoint-options.json")
        assertTrue(resource.exists(), "CLI must write META-INF/arc/endpoint-options.json when --endpoint-options-output is supplied")
    }

    @Test
    fun `CLI endpoint-options resource for non-default options is byte-identical to the Gradle task output`() {
        val manifestClasspath = writeMinimalManifest()
        val outputDir = temporaryDirectory.resolve("generated").toFile()
        val cliEndpointOptionsDir = temporaryDirectory.resolve("cli-endpoint-options").toFile()

        // Run the CLI with a non-default segmentsToSkipForRoute so the value is distinct.
        GenerateArcProxiesCli.main(
            arrayOf(
                "--manifest-classpath", manifestClasspath.absolutePath,
                "--output-directory", outputDir.absolutePath,
                "--route-prefix", "api",
                "--route-segments-to-skip", "6",
                "--include-command-names", "true",
                "--include-query-names", "true",
                "--enable-query-http-method", "true",
                "--endpoint-options-output", cliEndpointOptionsDir.absolutePath
            )
        )

        // Produce the same file through the Gradle task so we can compare bytes.
        val taskOutputDir = temporaryDirectory.resolve("task-output").toFile()
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.toFile()).build()
        val task = project.tasks.register(
            "writeArcEndpointOptionsResource",
            WriteArcEndpointOptionsResource::class.java
        ) { t ->
            t.routePrefix.set("api")
            t.segmentsToSkipForRoute.set(6)
            t.includeCommandNameInRoute.set(true)
            t.includeQueryNameInRoute.set(true)
            t.enableQueryHttpMethod.set(true)
            t.outputDirectory.set(taskOutputDir)
        }.get()
        task.write()

        val cliResource = File(cliEndpointOptionsDir, "META-INF/arc/endpoint-options.json")
        val taskResource = File(taskOutputDir, "META-INF/arc/endpoint-options.json")

        assertTrue(cliResource.exists(), "CLI resource must exist")
        assertTrue(taskResource.exists(), "Gradle task resource must exist")

        assertArrayEquals(
            taskResource.readBytes(),
            cliResource.readBytes(),
            "CLI and Gradle task must write byte-identical content for the same endpoint options; " +
                "a difference means the two build-time paths have drifted"
        )
    }

    @Test
    fun `CLI endpoint-options resource contains version 1 and the supplied non-default values`() {
        val manifestClasspath = writeMinimalManifest()
        val outputDir = temporaryDirectory.resolve("generated").toFile()
        val endpointOptionsDir = temporaryDirectory.resolve("endpoint-options-values").toFile()

        GenerateArcProxiesCli.main(
            arrayOf(
                "--manifest-classpath", manifestClasspath.absolutePath,
                "--output-directory", outputDir.absolutePath,
                "--route-prefix", "my-api",
                "--route-segments-to-skip", "6",
                "--include-command-names", "false",
                "--include-query-names", "true",
                "--enable-query-http-method", "false",
                "--endpoint-options-output", endpointOptionsDir.absolutePath
            )
        )

        val content = File(endpointOptionsDir, "META-INF/arc/endpoint-options.json").readText()
        val node = ArcObjectMapper.create().readTree(content)

        assertTrue(node.path("version").asInt(-1) == 1, "version must be 1")
        assertTrue(node.path("routePrefix").stringValue() == "my-api", "routePrefix must match")
        assertTrue(node.path("segmentsToSkipForRoute").asInt() == 6, "segmentsToSkipForRoute must match")
        assertFalse(node.path("includeCommandNameInRoute").asBoolean(), "includeCommandNameInRoute must be false")
        assertTrue(node.path("includeQueryNameInRoute").asBoolean(), "includeQueryNameInRoute must be true")
        assertFalse(node.path("enableQueryHttpMethod").asBoolean(), "enableQueryHttpMethod must be false")
    }

    // ── flag absent ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `CLI without endpoint-options-output flag writes no resource and generates proxies unchanged`() {
        val manifestClasspath = writeMinimalManifest()
        val outputDir = temporaryDirectory.resolve("generated-no-options").toFile()

        // Must not throw; must not write any endpoint-options.json anywhere.
        GenerateArcProxiesCli.main(
            arrayOf(
                "--manifest-classpath", manifestClasspath.absolutePath,
                "--output-directory", outputDir.absolutePath
            )
        )

        val anyResource = temporaryDirectory.toFile()
            .walkTopDown()
            .any { it.name == "endpoint-options.json" }
        assertFalse(anyResource, "No endpoint-options.json must be written when --endpoint-options-output is absent")
    }
}
