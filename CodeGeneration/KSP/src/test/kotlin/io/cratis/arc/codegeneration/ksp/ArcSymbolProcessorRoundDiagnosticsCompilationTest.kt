// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorRoundDiagnosticsCompilationTest {
    @Test
    fun `final ambiguity is emitted once at the handler in declaration order`() {
        val observation = Observation()
        val compilation = compile(listOf(SourceFile.kotlin("Ambiguous.kt", """
            package round.diagnostics
            public class Zed(public val value: String)
            public class Alpha(public val value: String)
            @io.cratis.arc.artifacts.Command
            public class Initial {
                public fun handle(): Pair<Zed, Alpha> = Pair(Zed("z"), Alpha("a"))
            }
        """.trimIndent())), observation)
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val diagnostic = observation.diagnostics.single { "[ARCKSP0109]" in it.message }
        assertTrue("'round.diagnostics.Zed', 'round.diagnostics.Alpha'" in diagnostic.message)
        assertEquals("handle", diagnostic.name)
        assertEquals(6, diagnostic.line)
        assertEquals("finish", diagnostic.phase)
        assertTrue(result.messages.contains("Ambiguous.kt:6"), result.messages)
        assertEquals(1, Regex("\\[ARCKSP0109]").findAll(result.messages).count())
        assertNoAggregate(compilation)
    }

    @Test
    fun `terminal graph diagnostics retain real property nodes and actual source lines`() {
        val observation = Observation()
        val compilation = compile(listOf(invalidResponse), observation)
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertPropertyDiagnostic(observation, "finish")
        assertTrue("Invalid.kt:2" in result.messages, result.messages)
        assertTrue(observation.rounds >= 2)
        assertNoAggregate(compilation)
    }

    @Test
    fun `own fatal invocation error flushes current graph diagnostics through onError`() {
        val observation = Observation()
        val compilation = compile(listOf(invalidResponse, SourceFile.kotlin("BadCommand.kt", """
            package round.diagnostics
            @io.cratis.arc.artifacts.Command public class BadCommand
        """.trimIndent())), observation)
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0102]" in result.messages, result.messages)
        assertPropertyDiagnostic(observation, "onError")
        assertNoAggregate(compilation)
    }

    @Test
    fun `another processor error before or after Arc flushes only current round diagnostics`() {
        listOf(false, true).forEach { before ->
            val observation = Observation()
            val provider = object : SymbolProcessorProvider {
                override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        environment.logger.error("Other processor failure")
                        return emptyList()
                    }
                }
            }
            val compilation = compile(listOf(invalidResponse), observation, provider, before)
            val result = compilation.compile()
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertEquals(1, observation.rounds)
            assertPropertyDiagnostic(observation, "onError")
            assertNoAggregate(compilation)
        }
    }

    @Test
    fun `artifact free configuration and handler errors are not hidden by finalization`() {
        val observation = Observation()
        val compilation = compile(listOf(SourceFile.kotlin("InvalidHandler.kt", """
            package round.diagnostics
            @io.cratis.arc.commands.HandlesCommandResponseValues(String::class)
            public class InvalidHandler
        """.trimIndent())), observation).apply { kspProcessorOptions = mutableMapOf() }
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertEquals(1, Regex("\\[ARCKSP0001]").findAll(result.messages).count())
        assertEquals(1, Regex("\\[ARCKSP0102]").findAll(result.messages).count())
        assertTrue("InvalidHandler.kt:3" in result.messages, result.messages)
        assertNoAggregate(compilation)
    }

    @Test
    fun `late blank and duplicate derived ids retain proxy codes and declaration sites`() {
        listOf("", "same").forEach { id ->
            val observation = Observation()
            val provider = object : SymbolProcessorProvider {
                override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                    private var generated = false
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        if (!generated) {
                            generated = true
                            environment.codeGenerator.createNewFile(
                                Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
                                "round.diagnostics", "LateLeaf"
                            ).bufferedWriter().use { it.write("""
                                package round.diagnostics
                                @io.cratis.arc.polymorphism.DerivedType("$id")
                                public class LateLeaf : Shape
                            """.trimIndent()) }
                        }
                        return emptyList()
                    }
                }
            }
            val compilation = compile(listOf(SourceFile.kotlin("Roots.kt", """
                package round.diagnostics
                public interface Shape
                @io.cratis.arc.polymorphism.DerivedType("same") public class EarlyLeaf : Shape
                @io.cratis.arc.artifacts.Command
                public class Initial(public val shape: Shape) { public fun handle(): Unit = Unit }
            """.trimIndent())), observation, provider)
            val result = compilation.compile()
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertFalse("ARCKSP9999" in result.messages, result.messages)
            val diagnostic = observation.diagnostics.single { "[ARCKSP0300]" in it.message }
            assertEquals(if (id.isEmpty()) "LateLeaf" else "EarlyLeaf", diagnostic.name)
            assertEquals(3, diagnostic.line)
            assertEquals("finish", diagnostic.phase)
            assertNoAggregate(compilation)
        }
    }

    @Test
    fun `unresolved handler annotation argument is explicitly deferred and retried`() {
        val observation = Observation()
        val provider = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                private var generated = false
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    if (!generated) {
                        generated = true
                        environment.codeGenerator.createNewFile(
                            Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()), "round.diagnostics", "Missing"
                        ).bufferedWriter().use { it.write("package round.diagnostics\npublic class Missing") }
                    }
                    return emptyList()
                }
            }
        }
        val compilation = compile(listOf(SourceFile.kotlin("DeferredHandler.kt", """
            package round.diagnostics
            import io.cratis.arc.commands.CommandContext
            import io.cratis.arc.commands.CommandResponseValueHandler
            import io.cratis.arc.commands.HandlesCommandResponseValues
            import io.cratis.arc.results.CommandResult
            @HandlesCommandResponseValues(Missing::class)
            public class DeferredHandler : CommandResponseValueHandler {
                override fun canHandle(context: CommandContext, value: Any): Boolean = value.javaClass.name == "round.diagnostics.Missing"
                override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = CommandResult.success(context.correlationId)
            }
        """.trimIndent())), observation, provider)
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(observation.deferred.any { it.first == "DeferredHandler" }, observation.deferred.toString())
        // This real KSP fixture also fails declaration validation; do not simulate a semantic symbol to force true.
        assertTrue(observation.deferred.any { it.first == "DeferredHandler" && !it.second }, observation.deferred.toString())
        assertTrue(observation.rounds >= 2)
        assertTrue(observation.diagnostics.isEmpty(), result.messages)
        assertNoAggregate(compilation)
    }

    private val invalidResponse = SourceFile.kotlin("Invalid.kt", """
        package round.diagnostics
        public class Payload(public val invalid: List<List<String>>)
        @io.cratis.arc.artifacts.Command
        public class Initial { public fun handle(): Payload = Payload(emptyList()) }
    """.trimIndent())

    private fun assertPropertyDiagnostic(observation: Observation, phase: String) {
        val diagnostic = observation.diagnostics.single { "[ARCKSP0300]" in it.message }
        assertTrue(diagnostic.property)
        assertEquals("invalid", diagnostic.name)
        assertEquals(2, diagnostic.line)
        assertEquals(phase, diagnostic.phase)
    }

    private fun assertNoAggregate(compilation: KotlinCompilation) {
        val files = compilation.workingDir.resolve("ksp/sources").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertFalse(files.any { "ArcArtifactMetadata" in it || "ArcArtifactModule" in it || it == "RoundDiagnostics.json" }, files.toString())
    }

    private data class Diagnostic(val message: String, val name: String?, val line: Int?, val property: Boolean, val phase: String)
    private class Observation {
        var rounds = 0
        var phase = "create"
        val diagnostics = mutableListOf<Diagnostic>()
        val deferred = mutableListOf<Pair<String?, Boolean>>()
    }

    private fun compile(
        sources: List<SourceFile>,
        observation: Observation,
        other: SymbolProcessorProvider? = null,
        before: Boolean = false
    ): KotlinCompilation {
        val arc = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
                val logger = object : KSPLogger by environment.logger {
                    override fun error(message: String, symbol: KSNode?) {
                        observation.diagnostics += Diagnostic(message, (symbol as? KSDeclaration)?.simpleName?.asString(),
                            (symbol?.location as? FileLocation)?.lineNumber, symbol is KSPropertyDeclaration, observation.phase)
                        environment.logger.error(message, symbol)
                    }
                }
                val processor = ArcSymbolProcessor(SymbolProcessorEnvironment(environment.options, environment.kotlinVersion,
                    environment.codeGenerator, logger, environment.apiVersion, environment.compilerVersion,
                    environment.platforms, environment.kspVersion))
                return object : SymbolProcessor {
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        observation.rounds++
                        observation.phase = "process"
                        return processor.process(resolver).also { deferred ->
                            observation.deferred += deferred.map { (it as? KSDeclaration)?.simpleName?.asString() to it.validate() }
                        }
                    }
                    override fun finish() {
                        observation.phase = "finish"
                        processor.finish()
                    }
                    override fun onError() {
                        observation.phase = "onError"
                        processor.onError()
                    }
                }
            }
        }
        return KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            inheritClassPath = true
            symbolProcessorProviders = (if (before) listOfNotNull(other, arc) else listOfNotNull(arc, other)).toMutableList()
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "RoundDiagnostics")
            kspWithCompilation = true
            messageOutputStream = System.out
        }
    }
}
