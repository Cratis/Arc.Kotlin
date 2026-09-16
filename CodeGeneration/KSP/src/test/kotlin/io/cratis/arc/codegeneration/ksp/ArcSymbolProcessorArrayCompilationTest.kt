// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.SequenceKind
import java.nio.file.Path
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorArrayCompilationTest {
    @Test
    fun `ordinary Java arrays and invariant Kotlin arrays generate concrete sequence parameters`() {
        val observations = linkedSetOf<String>()
        val observer = SymbolProcessorProvider {
            object : SymbolProcessor {
                override fun process(resolver: com.google.devtools.ksp.processing.Resolver): List<com.google.devtools.ksp.symbol.KSAnnotated> {
                    resolver.getSymbolsWithAnnotation("io.cratis.arc.artifacts.ReadModel")
                        .filterIsInstance<KSClassDeclaration>().forEach { declaration ->
                            (sequenceOf(declaration) + declaration.declarations.filterIsInstance<KSClassDeclaration>())
                                .flatMap { it.getDeclaredFunctions() }.forEach { function ->
                                function.parameters.forEach { parameter ->
                                    val type = parameter.type.resolve()
                                    type.arguments.forEach { argument ->
                                        observations.add("${declaration.simpleName.asString()}.${parameter.name?.asString()}: node=${parameter.origin} ref=${parameter.type.origin} argument=${argument.origin}/${argument.variance} element=${argument.type?.origin} elementRef=${argument.type?.element?.origin}")
                                    }
                                }
                            }
                        }
                    return emptyList()
                }
            }
        }
        val result = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(
                SourceFile.kotlin("KotlinArrays.kt", """
                    package arrays.fixtures
                    import io.cratis.arc.artifacts.ReadModel
                    import java.util.UUID
                    @ReadModel public data class KotlinArrays(public val value: String) {
                        public companion object {
                            public fun arrays(ids: Array<UUID>, longs: Array<Long>): KotlinArrays =
                                KotlinArrays(ids.single().toString() + longs.single().inc())
                        }
                    }
                """.trimIndent()),
                SourceFile.java("JavaArrays.java", """
                    package arrays.fixtures;
                    import io.cratis.arc.artifacts.ReadModel;
                    import java.util.UUID;
                    @ReadModel public record JavaArrays(String value) {
                        public static JavaArrays arrays(UUID[] ids, Long[] longs) {
                            return new JavaArrays(ids[0].toString() + (longs[0].longValue() + 1));
                        }
                    }
                """.trimIndent())
            )
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(observer, ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "Arrays")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(setOf(
            "KotlinArrays.ids: node=KOTLIN ref=KOTLIN argument=SYNTHETIC/INVARIANT element=SYNTHETIC elementRef=null",
            "KotlinArrays.longs: node=KOTLIN ref=KOTLIN argument=SYNTHETIC/INVARIANT element=SYNTHETIC elementRef=null",
            "JavaArrays.ids: node=JAVA ref=JAVA argument=SYNTHETIC/COVARIANT element=SYNTHETIC elementRef=null",
            "JavaArrays.longs: node=JAVA ref=JAVA argument=SYNTHETIC/COVARIANT element=SYNTHETIC elementRef=null"
        ), observations)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.ArraysArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule
        assertEquals(2, module.queryPerformers.size)
        module.queryPerformers.forEach { performer ->
            assertEquals(listOf(SequenceKind.ARRAY, SequenceKind.ARRAY), performer.descriptor.parameters.map { it.shape.sequenceKind })
            assertEquals(listOf("java.util.UUID", "kotlin.Long"), performer.descriptor.parameters.map { it.shape.elementShape?.typeName })
        }
    }

    @Test
    fun `array parameter support does not admit explicit projections generic elements or nullable entries`() {
        val root = Path.of(System.getProperty("arc.contractNegativeFixtures"))
        val result = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(
                SourceFile.kotlin("ProjectedArrayQueries.kt", root.resolve("kotlin/io/cratis/arc/contracts/negative/ProjectedArrayQueries.kt").toFile().readText()),
                SourceFile.java("GenericArrayQueries.java", root.resolve("java/io/cratis/arc/contracts/negative/GenericArrayQueries.java").toFile().readText())
            )
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "InvalidArrays")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        listOf("covariant", "contravariant", "starred").forEach { method ->
            assertTrue(
                "[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.ProjectedArrayQueries.$method.ids' value path 'element': raw and wildcard arguments are unsupported; star projections are unsupported; variant generic arguments are unsupported." in result.messages,
                result.messages
            )
        }
        assertTrue("[ARCKSP0300] Artifact/property 'io.cratis.arc.contracts.negative.ProjectedArrayQueries.nullableEntries.ids' value path 'element': nullable sequence elements are unsupported." in result.messages, result.messages)
        listOf("wildcard", "raw").forEach { method ->
            assertTrue("'io.cratis.arc.contracts.negative.GenericArrayQueries.$method.ids' uses an unsupported nested or generic collection element shape." in result.messages, result.messages)
        }
        assertTrue("GenericArrayQueries.parameter" in result.messages && "[ARCKSP0201]" in result.messages, result.messages)
    }
}
