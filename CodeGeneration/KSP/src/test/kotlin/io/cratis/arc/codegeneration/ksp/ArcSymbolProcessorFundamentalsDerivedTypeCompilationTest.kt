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
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** KSP honours `io.cratis.fundamentals.serialization.DerivedType` like Arc's own (deprecated) annotation. */
@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorFundamentalsDerivedTypeCompilationTest {
    @TempDir
    lateinit var workingDirectory: File

    private val fundamentals = "io.cratis.fundamentals.serialization.DerivedType"
    private val legacy = "io.cratis.arc.polymorphism.DerivedType"

    private fun command(vararg leaves: String) = """
        package derived.annotations
        public interface Shape
        @io.cratis.arc.artifacts.Command
        public class Draw(public val shape: Shape) { public fun handle(): Unit = Unit }
        ${leaves.joinToString("\n")}
    """.trimIndent()

    private fun compile(vararg sources: SourceFile): Pair<JvmCompilationResult, KotlinCompilation> {
        val compilation = KotlinCompilation().apply {
            workingDir = workingDirectory
            useKsp2()
            this.sources = sources.toList()
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "DerivedAnnotations")
            kspWithCompilation = true
            messageOutputStream = System.out
        }
        return compilation.compile() to compilation
    }

    private fun module(result: JvmCompilationResult) =
        result.classLoader.loadClass("io.cratis.arc.generated.DerivedAnnotationsArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule

    private fun summary(module: ArcArtifactModule) = module.derivedTypes
        .map { "${it.baseType.name}->${it.derivedType.name}" } to module.types
        .filter { it.derivedTypeId != null }.associate { it.fullyQualifiedName to it.derivedTypeId }

    @Test
    fun `a fundamentals annotation generates the same registration and descriptor as the legacy annotation`() {
        val (fundamentalsResult, _) = compile(SourceFile.kotlin("Fundamentals.kt", command(
            "@$fundamentals(\"circle\") public class Circle(public val radius: Double) : Shape")))
        val legacyWorking = File(workingDirectory, "legacy").apply { mkdirs() }
        val legacyCompilation = KotlinCompilation().apply {
            workingDir = legacyWorking
            useKsp2()
            sources = listOf(SourceFile.kotlin("Legacy.kt", command(
                "@$legacy(\"circle\") public class Circle(public val radius: Double) : Shape")))
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "DerivedAnnotations")
            kspWithCompilation = true
            messageOutputStream = System.out
        }
        val legacyResult = legacyCompilation.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, fundamentalsResult.exitCode, fundamentalsResult.messages)
        assertEquals(KotlinCompilation.ExitCode.OK, legacyResult.exitCode, legacyResult.messages)
        val actual = summary(module(fundamentalsResult))
        assertEquals(listOf("derived.annotations.Shape->derived.annotations.Circle"), actual.first)
        assertEquals(mapOf("derived.annotations.Circle" to "circle"), actual.second)
        assertEquals(summary(module(legacyResult)), actual)
    }

    @Test
    fun `a java source derivative with the positional fundamentals annotation is registered`() {
        val (result, _) = compile(
            SourceFile.kotlin("Roots.kt", command()),
            SourceFile.java("JCircle.java", """
                package derived.annotations;
                @$fundamentals("j-circle")
                public class JCircle implements Shape { }
            """.trimIndent())
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(mapOf("derived.annotations.JCircle" to "j-circle"), summary(module(result)).second)
    }

    @Test
    fun `both annotations with the same id compile without a diagnostic`() {
        val (result, _) = compile(SourceFile.kotlin("Both.kt", command(
            "@$fundamentals(\"both\") @$legacy(\"both\") public class Both : Shape")))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse("[ARCKSP0303]" in result.messages, result.messages)
        assertEquals(mapOf("derived.annotations.Both" to "both"), summary(module(result)).second)
    }

    @Test
    fun `both annotations with different ids warn and keep the arc id`() {
        val (result, _) = compile(SourceFile.kotlin("Both.kt", command(
            "@$fundamentals(\"f-id\") @$legacy(\"a-id\") public class Both : Shape")))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue("[ARCKSP0303]" in result.messages && "'a-id'" in result.messages && "'f-id'" in result.messages, result.messages)
        assertEquals(mapOf("derived.annotations.Both" to "a-id"), summary(module(result)).second)
    }

    @Test
    fun `a blank fundamentals id is ARCKSP0303`() {
        val (result, _) = compile(SourceFile.kotlin("Blank.kt", command("@$fundamentals(\"\") public class Blank : Shape")))

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0303]" in result.messages, result.messages)
    }

    @Test
    fun `a fundamentals annotation on an interface is ARCKSP0304`() {
        val (result, _) = compile(SourceFile.kotlin("Iface.kt", """
            package derived.annotations
            @$fundamentals("iface") public interface Sub
            @io.cratis.arc.artifacts.Command
            public class Draw(public val sub: Sub) { public fun handle(): Unit = Unit }
        """.trimIndent()))

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0304]" in result.messages, result.messages)
    }

    @Test
    fun `the same id across mixed annotations under one base is ambiguous`() {
        val (result, _) = compile(SourceFile.kotlin("Mixed.kt", command(
            "@$fundamentals(\"dup\") public class A : Shape",
            "@$legacy(\"dup\") public class B : Shape")))

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("ambiguous @DerivedType id 'dup'" in result.messages, result.messages)
    }
}
