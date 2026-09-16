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
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeKind
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorSequenceCompilationTest {
    @TempDir lateinit var work: File

    @Test
    fun `nullable entries fail at ordinary command model interface and read model properties`() {
        val result = compile(listOf(fixture("kotlin", "NullableSequenceProperties.kt")))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (owner in listOf("NullableListProperty", "NullableCollectionProperty", "NullableArrayProperty",
            "NullableSequenceModel", "NullableSequenceView", "NullableSequenceReadModel")) {
            assertTrue(diagnostic("io.cratis.arc.contracts.negative.$owner.values") in result.messages, result.messages)
        }
        assertFalse(manifest().exists(), "Invalid entries must not produce a manifest")
    }

    @Test
    fun `explicit Java record type use nullable entries fail rather than disappearing in fallback`() {
        val sources = listOf(fixture("java", "Nullable.java"), fixture("java", "NullableJavaSequenceProperty.java"))
        val java = KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            jvmTarget = "17"
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, java.exitCode, java.messages)
        val result = compile(sources)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(diagnostic("io.cratis.arc.contracts.negative.NullableJavaSequenceProperty.values") in result.messages, result.messages)
        assertFalse(manifest().exists())
    }

    @Test
    fun `nullable outer sequences preserve nonnullable entries and Java platform lists remain supported`() {
        val result = compile(listOf(
            SourceFile.kotlin("Sequences.kt", """
                package sequences
                import io.cratis.arc.artifacts.Command
                import io.cratis.arc.artifacts.ReadModel
                public interface SequenceView { public val values: Collection<String>? }
                public data class SequenceModel(public val values: Array<String>?)
                @ReadModel
                public data class SequenceReadModel(public val values: List<String>?) {
                    public companion object { @JvmStatic public fun all(): SequenceReadModel = SequenceReadModel(null) }
                }
                @Command
                public data class Sequences(
                    public val list: List<String>?,
                    public val collection: Collection<String>?,
                    public val array: Array<String>?,
                    public val view: SequenceView,
                    public val model: SequenceModel
                ) { public fun handle() { } }
            """.trimIndent()),
            SourceFile.java("JavaSequences.java", """
                package sequences;
                @io.cratis.arc.artifacts.Command
                public record JavaSequences(java.util.List<String> values) { public void handle() { } }
            """.trimIndent())
        ))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.SequencesArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule
        val properties = module.commandHandlers.single { it.metadata.name == "Sequences" }.metadata.properties
        for ((name, kind) in listOf("list" to SequenceKind.LIST, "collection" to SequenceKind.COLLECTION, "array" to SequenceKind.ARRAY)) {
            val shape = properties.single { it.name == name }.shape
            assertEquals(TypeShapeKind.SEQUENCE, shape.kind)
            assertEquals(kind, shape.sequenceKind)
            assertTrue(shape.nullable)
            assertEquals(false, shape.elementShape?.nullable)
        }
        for (name in listOf("SequenceModel", "SequenceReadModel")) {
            val shape = module.types.single { it.name == name }.properties.single().shape
            assertTrue(shape.nullable)
            assertEquals(false, shape.elementShape?.nullable)
        }
        val view = module.interfaces.single().properties.single().shape
        assertTrue(view.nullable)
        assertEquals(false, view.elementShape?.nullable)
        val java = module.commandHandlers.single { it.metadata.name == "JavaSequences" }.metadata.properties
        assertEquals(listOf(SequenceKind.LIST), java.map { it.shape.sequenceKind })
        assertTrue(java.all { it.shape.elementShape?.nullable == false })
        assertTrue(manifest().isFile)
    }

    private fun diagnostic(identity: String): String = "[ARCKSP0300] Artifact/property '$identity' value path 'value[]': " +
        "nullable sequence elements are unsupported; declare nonnullable elements, for example List<T> or List<T>?."

    private fun fixture(language: String, name: String): SourceFile {
        val file = File(System.getProperty("arc.contractNegativeFixtures"))
            .resolve("$language/io/cratis/arc/contracts/negative/$name")
        return if (language == "java") SourceFile.java(name, file.readText()) else SourceFile.kotlin(name, file.readText())
    }

    private fun manifest(): File = work.resolve("ksp/sources/resources/META-INF/cratis/arc/Sequences.json")

    private fun compile(sources: List<SourceFile>): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources
        workingDir = work
        inheritClassPath = true
        jvmTarget = "17"
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "Sequences")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
