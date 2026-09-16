// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorExportedTypeCompilationTest {
    @TempDir
    lateinit var workingDirectory: File

    @Test
    fun `module with only exported types emits an artifact module and a manifest`() {
        val result = compile(listOf(kotlinExports(), javaExport()))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.ExportRootsArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule

        // The whole point of the wave: no command and no query, yet the compilation still contributes artifacts.
        assertTrue(module.commandHandlers.isEmpty(), "exported types must not invent command handlers")
        assertTrue(module.queryPerformers.isEmpty(), "exported types must not invent query performers")

        assertEquals(
            setOf("exports.SampleIdentityDetails", "exports.Nested", "exports.JavaDetails"),
            module.types.map { it.fullyQualifiedName }.toSet()
        )
        assertEquals(listOf("exports.Flavor"), module.enums.map { it.fullyQualifiedName })
        assertEquals(listOf("exports.View"), module.interfaces.map { it.fullyQualifiedName })

        // Reachability still applies: Nested is exported because SampleIdentityDetails references it.
        val details = module.types.single { it.fullyQualifiedName == "exports.SampleIdentityDetails" }
        assertEquals(listOf("nested", "source"), details.properties.map { it.name }.sorted())

        val manifestFile = workingDirectory.resolve("ksp/sources/resources/META-INF/cratis/arc/ExportRoots.json")
        assertTrue(manifestFile.isFile, "an exported-type-only compilation must still write a manifest")
        val manifest = ArcObjectMapper.create().readValue(manifestFile, ArcArtifactManifest::class.java)
        assertEquals(ArcArtifactManifest.CURRENT_FORMAT_VERSION, manifest.formatVersion)
        assertEquals("ExportRoots", manifest.moduleName)
        assertTrue(manifest.commands.isEmpty())
        assertTrue(manifest.queries.isEmpty())
        assertTrue(manifest.types.any { it.fullyQualifiedName == "exports.JavaDetails" })

        val services = workingDirectory
            .resolve("ksp/sources/resources/META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule")
        assertTrue(services.isFile, "the generated module must be registered for runtime discovery")
        assertEquals(
            "io.cratis.arc.generated.ExportRootsArcArtifactModule",
            services.readText().trim()
        )
    }

    @Test
    fun `compilation with no roots at all still emits nothing`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "Unrelated.kt",
                    """
                    package exports

                    public data class Unrelated(public val value: String)
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(
            !workingDirectory.resolve("ksp/sources/resources/META-INF/cratis/arc/ExportRoots.json").isFile,
            "an unannotated type must not become a root; that is what --library-mode would do in Arc .NET"
        )
    }

    private fun kotlinExports() = SourceFile.kotlin(
        "Exports.kt",
        """
        package exports

        import io.cratis.arc.artifacts.ExportedType

        /** Identity details exported without any command or query referencing them. */
        @ExportedType
        public data class SampleIdentityDetails(public val source: String, public val nested: Nested)

        /** Reached through SampleIdentityDetails rather than annotated directly. */
        public data class Nested(public val flavor: Flavor)

        @ExportedType
        public enum class Flavor { FIRST, SECOND }

        @ExportedType
        public interface View {
            public val name: String
        }
        """.trimIndent()
    )

    private fun javaExport() = SourceFile.java(
        "JavaDetails.java",
        """
        package exports;

        import io.cratis.arc.artifacts.ExportedType;

        @ExportedType
        public record JavaDetails(String tenant) {
        }
        """.trimIndent()
    )

    private fun compile(sources: List<SourceFile>): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources
        workingDir = workingDirectory
        inheritClassPath = true
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "ExportRoots")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
