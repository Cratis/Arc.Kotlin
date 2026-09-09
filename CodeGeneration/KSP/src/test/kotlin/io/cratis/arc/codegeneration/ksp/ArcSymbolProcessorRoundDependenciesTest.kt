// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcObjectMapper
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorRoundDependenciesTest {
    @Test
    fun `aggregate outputs use terminal round files and retain later generated commands`() {
        val rounds = mutableListOf<List<KSFile>>()
        val roundNames = mutableListOf<List<String>>()
        val aggregateDependencies = linkedMapOf<String, Dependencies>()
        val observingProvider = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
                val generator = object : CodeGenerator by environment.codeGenerator {
                    override fun createNewFile(
                        dependencies: Dependencies,
                        packageName: String,
                        fileName: String,
                        extensionName: String
                    ): OutputStream {
                        if (fileName in setOf("RoundDependenciesArcArtifactModule", "RoundDependenciesArcArtifactMetadata")) {
                            aggregateDependencies[fileName] = dependencies
                        }
                        return environment.codeGenerator.createNewFile(dependencies, packageName, fileName, extensionName)
                    }

                    override fun createNewFileByPath(
                        dependencies: Dependencies,
                        path: String,
                        extensionName: String
                    ): OutputStream {
                        aggregateDependencies[path] = dependencies
                        return environment.codeGenerator.createNewFileByPath(dependencies, path, extensionName)
                    }
                }
                val processor = ArcSymbolProcessor(SymbolProcessorEnvironment(
                    environment.options, environment.kotlinVersion, generator, environment.logger,
                    environment.apiVersion, environment.compilerVersion, environment.platforms, environment.kspVersion
                ))
                return object : SymbolProcessor by processor {
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        val files = resolver.getAllFiles().toList()
                        rounds.add(files)
                        // Capture names while valid; never read filePath or dereference old symbols after a round.
                        roundNames.add(files.map { it.fileName })
                        return processor.process(resolver)
                    }
                }
            }
        }
        val compilation = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(
                SourceFile.kotlin("PackageMarker.kt", "package rounds"),
                SourceFile.java("Input.java", """
                    package rounds;
                    @io.cratis.arc.artifacts.Command
                    public record Input(String value) {
                        public String handle() { return "input:" + value; }
                    }
                """.trimIndent())
            )
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(observingProvider, LateCommandProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "RoundDependencies")
            kspWithCompilation = true
            messageOutputStream = System.out
        }
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(rounds.size >= 3)
        assertFalse(roundNames.first().contains("LateCommand.kt"))
        assertTrue(roundNames.last().containsAll(listOf("PackageMarker.kt", "Input.java", "LateCommand.kt")))
        assertEquals(
            setOf(
                "RoundDependenciesArcArtifactModule",
                "RoundDependenciesArcArtifactMetadata",
                "META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule",
                "META-INF/cratis/arc/RoundDependencies.json"
            ),
            aggregateDependencies.keys
        )
        aggregateDependencies.values.forEach { dependencies ->
            assertSame(aggregateDependencies.getValue("RoundDependenciesArcArtifactModule"), dependencies)
            assertTrue(dependencies.aggregating)
            assertFalse(dependencies.isAllSources)
            assertEquals(rounds.last().size, dependencies.originatingFiles.size)
            rounds.last().zip(dependencies.originatingFiles).forEach { (current, origin) -> assertSame(current, origin) }
            assertFalse(dependencies.originatingFiles.any { origin -> rounds.first().any { it === origin } })
        }

        val resources = compilation.workingDir.resolve("ksp/sources/resources")
        assertEquals(
            "io.cratis.arc.generated.RoundDependenciesArcArtifactModule\n",
            resources.resolve("META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule").readText()
        )
        val manifest = ArcObjectMapper.create().readTree(
            resources.resolve("META-INF/cratis/arc/RoundDependencies.json")
        )
        assertEquals(listOf("Input", "LateCommand"), manifest["commands"].values().map { it["name"].stringValue() })
        val module = result.classLoader.loadClass("io.cratis.arc.generated.RoundDependenciesArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        assertEquals(listOf("Input", "LateCommand"), module.commandHandlers.map { it.metadata.name })
        module.commandHandlers.forEach { handler ->
            val command = handler.commandType.getConstructor(String::class.java).newInstance("value")
            val context = CommandContext(
                UUID.randomUUID(), command, handler.commandType, ArcPrincipal.anonymous(),
                serviceResolver = object : ServiceResolver {
                    override fun <T : Any> resolve(type: Class<T>): T? = null
                }
            )
            val prefix = if (handler.metadata.name == "Input") "input" else "late"
            assertEquals("$prefix:value", runBlocking { handler.invoke(context) })
        }
    }

    private class LateCommandProvider : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
            private var generated = false

            override fun process(resolver: Resolver): List<KSAnnotated> {
                if (!generated) {
                    val dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray())
                    environment.codeGenerator.createNewFile(dependencies, "rounds", "LateCommand")
                        .bufferedWriter().use { writer ->
                            writer.write("""
                                package rounds
                                @io.cratis.arc.artifacts.Command
                                public class LateCommand(public val value: String) {
                                    public fun handle(): String = "late:" + value
                                }
                            """.trimIndent())
                        }
                    generated = true
                }
                return emptyList()
            }
        }
    }
}
