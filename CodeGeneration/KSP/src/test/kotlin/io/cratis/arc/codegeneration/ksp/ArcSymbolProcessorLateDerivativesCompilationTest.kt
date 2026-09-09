// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.json.ArcObjectMapper
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorLateDerivativesCompilationTest {
    @Test
    fun `plain Kotlin leaf discovered after early performer refreshes the whole reachable graph`() {
        verify(java = false, commands = false)
    }

    @Test
    fun `plain Java leaf discovered after early performer refreshes the whole reachable graph`() {
        verify(java = true, commands = false)
    }

    @Test
    fun `Kotlin command reentry and independent command properties agree with the final graph`() {
        verify(java = false, commands = true)
    }

    @Test
    fun `Java record command reentry and independent command properties agree with the final graph`() {
        verify(java = true, commands = true)
    }

    @Test
    fun `early concept command preserves scalar and command metadata without a type model`() {
        verifyConceptCommand(late = false)
    }

    @Test
    fun `late generated concept command preserves scalar and command metadata without a type model`() {
        verifyConceptCommand(late = true)
    }

    private fun verifyConceptCommand(late: Boolean) {
        val conceptCommand = """
            package late.concepts

            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.concepts.ConceptAs

            @Command
            public class Reset(public val raw: String) : ConceptAs<String> {
                override fun value(): String = raw
                public fun handle(): Unit = Unit
            }
        """.trimIndent()
        val leaf = """
            package late.concepts

            @io.cratis.arc.polymorphism.DerivedType("leaf")
            public class Leaf(public val value: String) : Shape
        """.trimIndent()
        val sources = mutableListOf(SourceFile.kotlin("Initial.kt", """
            package late.concepts

            public interface Shape
            @io.cratis.arc.artifacts.Command
            public class Initial(public val shape: Shape) {
                public fun handle(): Unit = Unit
            }
        """.trimIndent()))
        if (!late) {
            sources += SourceFile.kotlin("Reset.kt", conceptCommand)
            sources += SourceFile.kotlin("Leaf.kt", leaf)
        }
        val observations = mutableListOf<Boolean>()
        val provider = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                private var emitted = false
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val invoker = resolver.getClassDeclarationByName(resolver.getKSNameFromString(
                        "io.cratis.arc.generated.commands.${commandHandlerClassName("late.concepts.Initial")}"
                    ))
                    observations += invoker != null
                    if (late && !emitted && invoker != null) {
                        emitted = true
                        val dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray())
                        for ((name, source) in listOf("Reset" to conceptCommand, "Leaf" to leaf)) {
                            environment.codeGenerator.createNewFile(dependencies, "late.concepts", name)
                                .bufferedWriter().use { it.write(source) }
                        }
                    }
                    return emptyList()
                }
            }
        }
        val compilation = KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider(), provider)
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "ConceptCommands")
            kspWithCompilation = true
            messageOutputStream = System.out
        }
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(observations.first())
        assertTrue(observations.any { it })
        val module = result.classLoader.loadClass("io.cratis.arc.generated.ConceptCommandsArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        val mapper = ArcObjectMapper.create()
        val manifest = mapper.readValue(compilation.workingDir.resolve(
            "ksp/sources/resources/META-INF/cratis/arc/ConceptCommands.json"), ArcArtifactManifest::class.java)
        assertEquals(listOf("Initial", "Reset"), module.commandHandlers.map { it.metadata.name })
        assertEquals(listOf("Initial", "Leaf"), module.types.map { it.name })
        assertEquals(listOf("Shape"), module.interfaces.map { it.name })
        val concept = module.concepts.single()
        assertEquals("late.concepts.Reset", concept.fullyQualifiedName)
        assertEquals("kotlin.String", concept.underlyingTypeName)
        for (handler in module.commandHandlers) {
            val direct = handler.javaClass.getConstructor().newInstance() as CommandHandler
            assertEquals(mapper.writeValueAsString(handler.metadata), mapper.writeValueAsString(direct.metadata))
            val property = direct.metadata.properties.single()
            if (handler.metadata.name == "Reset") {
                assertEquals("raw", property.name)
                assertEquals("kotlin.String", property.shape.typeName)
                assertFalse(property.shape.nullable)
                assertTrue(property.derivatives.isEmpty())
            } else {
                assertEquals("shape", property.name)
                assertEquals("late.concepts.Shape", property.shape.typeName)
                assertEquals(listOf("late.concepts.Leaf"), property.derivatives)
                assertEquals(mapper.writeValueAsString(direct.metadata.properties), mapper.writeValueAsString(
                    module.types.single { it.name == "Initial" }.properties))
            }
        }
        assertEquals(mapper.writeValueAsString(module.concepts), mapper.writeValueAsString(manifest.concepts))
        assertEquals(mapper.writeValueAsString(module.types), mapper.writeValueAsString(manifest.types))
        assertEquals(mapper.writeValueAsString(module.commandHandlers.map { it.metadata }), mapper.writeValueAsString(manifest.commands))
    }

    private fun verify(java: Boolean, commands: Boolean) {
        val mapper = ArcObjectMapper.create()
        val manifests = listOf(false, true).map { late ->
            val leaf = if (java) """
                package late.derivatives;
                @io.cratis.arc.polymorphism.DerivedType(id = "leaf")
                public final class Leaf extends Base {
                    public View getOwner() { return null; }
                }
            """.trimIndent() else """
                package late.derivatives
                @io.cratis.arc.polymorphism.DerivedType("leaf")
                public class Leaf(public val owner: View?) : Base()
            """.trimIndent()
            val sources = mutableListOf(SourceFile.kotlin("Roots.kt", """
                package late.derivatives
                public interface Shape
                public abstract class Base : Shape
                public interface Carrier { public val shape: Shape }
                @io.cratis.arc.artifacts.ReadModel
                public data class View(public val shape: Shape, public val carrier: Carrier? = null) {
                    public companion object {
                        @JvmStatic public fun all(): List<View> = emptyList()
                    }
                }
                public interface Unrelated
                @io.cratis.arc.polymorphism.DerivedType("ignored")
                public class UnrelatedLeaf(public val invalid: List<List<String>>) : Unrelated
            """.trimIndent()))
            fun command(name: String): String = if (java) """
                package late.derivatives;
                @io.cratis.arc.artifacts.Command
                public record $name(Shape shape) { public void handle() {} }
            """.trimIndent() else """
                package late.derivatives
                @io.cratis.arc.artifacts.Command
                public class $name(public val shape: Shape) { public fun handle(): Unit = Unit }
            """.trimIndent()
            fun source(name: String, text: String): SourceFile = if (java) SourceFile.java("$name.java", text)
                else SourceFile.kotlin("$name.kt", text)
            if (!late) sources += source("Leaf", leaf)
            if (commands) sources += source("Initial", command("Initial"))
            val observations = mutableListOf<Boolean>()
            val provider = object : SymbolProcessorProvider {
                override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                    private var emitted = false
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        val earlyPerformer = resolver.getClassDeclarationByName(resolver.getKSNameFromString(
                            "io.cratis.arc.generated.queries.${queryPerformerClassName("late.derivatives.View.all")}"
                        ))
                        observations += earlyPerformer != null
                        // A real generated consumer waits for Arc's early invoker, not a fixed stabilization count.
                        if (!emitted && earlyPerformer != null) {
                            emitted = true
                            val dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray())
                            fun emit(name: String, text: String) {
                                environment.codeGenerator.createNewFile(dependencies, "late.derivatives", name,
                                    if (java) "java" else "kt").bufferedWriter().use { it.write(text) }
                            }
                            if (late) emit("Leaf", leaf)
                            if (commands) emit("Reentry", command("Reentry"))
                        }
                        return emptyList()
                    }
                }
            }
            val compilation = KotlinCompilation().apply {
                useKsp2()
                this.sources = sources
                inheritClassPath = true
                symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider(), provider)
                kspProcessorOptions = mutableMapOf("arc.moduleName" to "LateDerivatives")
                kspWithCompilation = true
                messageOutputStream = System.out
            }
            val result = compilation.compile()
            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
            assertFalse(observations.first())
            assertTrue(observations.any { it })
            val module = result.classLoader.loadClass("io.cratis.arc.generated.LateDerivativesArcArtifactModule")
                .getConstructor().newInstance() as ArcArtifactModule
            val manifest = mapper.readValue(compilation.workingDir.resolve(
                "ksp/sources/resources/META-INF/cratis/arc/LateDerivatives.json"), ArcArtifactManifest::class.java)
            val expectedTypes = listOf("Base", "Leaf", "View") + if (commands) listOf("Initial", "Reentry") else emptyList()
            assertEquals(expectedTypes.sorted(), module.types.map { it.name }.sorted())
            assertEquals(listOf("Carrier", "Shape"), module.interfaces.map { it.name })
            val leafType = module.types.single { it.name == "Leaf" }
            assertEquals("leaf", leafType.derivedTypeId)
            assertEquals("late.derivatives.Base", leafType.baseTypeName)
            val expectedDerivatives = listOf("late.derivatives.Leaf")
            assertEquals(expectedDerivatives, module.types.single { it.name == "View" }.properties.single { it.name == "shape" }.derivatives)
            assertEquals(expectedDerivatives, module.interfaces.single { it.name == "Carrier" }.properties.single().derivatives)
            assertEquals(mapper.writeValueAsString(module.types), mapper.writeValueAsString(manifest.types))
            assertEquals(mapper.writeValueAsString(module.interfaces), mapper.writeValueAsString(manifest.interfaces))
            assertEquals(mapper.writeValueAsString(module.queryPerformers.map { it.descriptor }), mapper.writeValueAsString(manifest.queries))
            module.commandHandlers.forEach { handler ->
                val direct = handler.javaClass.getConstructor().newInstance() as CommandHandler
                assertEquals(expectedDerivatives, direct.metadata.properties.single().derivatives)
                assertEquals(mapper.writeValueAsString(direct.metadata), mapper.writeValueAsString(handler.metadata))
                assertEquals(mapper.writeValueAsString(handler.metadata.properties), mapper.writeValueAsString(
                    module.types.single { it.fullyQualifiedName == handler.metadata.typeName }.properties))
            }
            assertEquals(mapper.writeValueAsString(module.commandHandlers.map { it.metadata }), mapper.writeValueAsString(manifest.commands))
            mapper.writeValueAsString(manifest)
        }
        assertEquals(manifests.first(), manifests.last(), "early and late discovery must produce identical final metadata")
    }
}
