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
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeKind
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorMapCompilationTest {
    @Test
    fun `Kotlin and Java map properties emit identical recursive runtime-safe shapes deterministically`() {
        val sources = positiveSources()
        val firstDirectory = Files.createTempDirectory("arc-map-first").toFile()
        val secondDirectory = Files.createTempDirectory("arc-map-second").toFile()
        val first = compile(sources, firstDirectory)
        val second = compile(sources, secondDirectory)

        assertEquals(KotlinCompilation.ExitCode.OK, first.exitCode, first.messages)
        assertEquals(KotlinCompilation.ExitCode.OK, second.exitCode, second.messages)
        val resource = "ksp/sources/resources/META-INF/cratis/arc/MapMetadata.json"
        assertTrue(firstDirectory.resolve(resource).readBytes().contentEquals(secondDirectory.resolve(resource).readBytes()))

        val commands = module(first).commandHandlers.associate { handler -> handler.metadata.name to handler.metadata }
        listOf("KotlinMapCommand", "JavaMapCommand").forEach { commandName ->
            val properties = commands.getValue(commandName).properties.associateBy { property -> property.name }
            val strings = properties.getValue("strings").shape
            assertEquals(TypeShapeKind.MAP, strings.kind)
            assertEquals(MapKeyCodec.STRING, strings.keyCodec)
            assertEquals(TypeShapeKind.VALUE, strings.keyShape?.kind)
            assertTrue(strings.keyShape?.typeName in setOf("kotlin.String", "java.lang.String"))
            assertTrue(strings.valueShape?.typeName in setOf("kotlin.String", "java.lang.String"))

            val numbers = properties.getValue("numbers").shape
            assertEquals(TypeShapeKind.SEQUENCE, numbers.valueShape?.kind)
            assertEquals(SequenceKind.LIST, numbers.valueShape?.sequenceKind)
            assertTrue(numbers.valueShape?.elementShape?.typeName in setOf("kotlin.Int", "java.lang.Integer"))

            val nested = properties.getValue("nested").shape
            assertEquals(TypeShapeKind.MAP, nested.valueShape?.kind)
            assertTrue(nested.valueShape?.valueShape?.typeName in setOf("kotlin.Boolean", "java.lang.Boolean"))
        }

        val kotlinCommand = first.classLoader.loadClass("map.fixtures.KotlinMapCommand").getDeclaredConstructor().newInstance()
        val javaCommand = first.classLoader.loadClass("map.fixtures.JavaMapCommand").getDeclaredConstructor().newInstance()
        assertTrue(kotlinCommand.javaClass.getMethod("getStrings").invoke(kotlinCommand) is Map<*, *>)
        assertTrue(javaCommand.javaClass.getMethod("strings").invoke(javaCommand) is Map<*, *>)
    }

    @Test
    fun `outer-nullable Java map property is accepted without making its entries nullable`() {
        val result = compile(outerNullableJavaMapSources())

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val property = module(result).commandHandlers.single().metadata.properties.single()
        assertEquals("values", property.name)
        assertTrue(property.shape.nullable)
        assertEquals(TypeShapeKind.VALUE, property.shape.valueShape?.kind)
        assertEquals("java.lang.String", property.shape.valueShape?.typeName)
    }

    @Test
    fun `Java nullable generic map arguments raw maps and wildcards report exact paths`() {
        val result = compile(invalidJavaMapShapeSources())

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val expected = listOf(
            "Artifact/property 'javamap.invalid.NullableKeyMapCommand.values' value path 'value.key': map keys must be nonnullable String.",
            "Artifact/property 'javamap.invalid.NullableValueMapCommand.values' value path 'value': nullable map values and sequence elements are unsupported.",
            "Artifact/property 'javamap.invalid.NullableListElementMapCommand.values' value path 'value[]': nullable map values and sequence elements are unsupported.",
            "Artifact/property 'javamap.invalid.NullableNestedMapValueCommand.values' value path 'value.value': nullable map values and sequence elements are unsupported.",
            "Artifact/property 'javamap.invalid.RawMapCommand.values' value path 'value': raw, wildcard, and type-parameter map arguments are unsupported; star projections are unsupported.",
            "Artifact/property 'javamap.invalid.WildcardMapCommand.values' value path 'value': raw and wildcard arguments are unsupported; star projections are unsupported."
        )
        expected.forEach { message ->
            assertTrue("[ARCKSP0300] $message" in result.messages, "Missing '$message' in:\n${result.messages}")
        }
        assertEquals(expected.size, Regex("\\[ARCKSP0300]").findAll(result.messages).count(), result.messages)
    }

    @Test
    fun `unsupported map matrix reports path-aware stable proxy-shape diagnostics`() {
        val result = compile(negativeSources())

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        listOf(
            "value path 'value.key': map keys must be nonnullable String",
            "value path 'value': nullable map values and sequence elements are unsupported",
            "value path 'value[]': nullable map values and sequence elements are unsupported",
            "not a runtime-safe JavaScript primitive",
            "Artifact/property 'map.invalid.FloatValue.values' value path 'value': map value leaf 'kotlin.Float'",
            "Artifact/property 'map.invalid.DoubleValue.values' value path 'value': map value leaf 'kotlin.Double'",
            "Artifact/property 'map.invalid.PrimitiveFloatJavaMapCommand.values' value path 'value[]': map value leaf 'java.lang.Float'",
            "Artifact/property 'map.invalid.PrimitiveDoubleJavaMapCommand.values' value path 'value[]': map value leaf 'java.lang.Double'",
            "Artifact/property 'map.invalid.BoxedFloatJavaMapCommand.values' value path 'value': map value leaf 'java.lang.Float'",
            "Artifact/property 'map.invalid.BoxedDoubleJavaMapCommand.values' value path 'value': map value leaf 'java.lang.Double'",
            "value path 'conceptValue' has unsupported non-scalar underlying type",
            "star projections are unsupported",
            "query, observable, and service parameters cannot use maps",
            "query and observable return maps are unsupported",
            "uses a top-level map; command response maps are unsupported",
            "maps are supported only for artifact properties"
        ).forEach { message ->
            assertTrue("[ARCKSP0300]" in result.messages && message in result.messages, "Missing '$message' in:\n${result.messages}")
        }
    }

    private fun positiveSources(): List<SourceFile> = listOf(
        SourceFile.kotlin(
            "KotlinMapCommand.kt",
            """
            package map.fixtures

            import io.cratis.arc.artifacts.Command

            @Command
            public data class KotlinMapCommand(
                public val strings: Map<String, String> = emptyMap(),
                public val numbers: Map<String, List<Int>> = emptyMap(),
                public val nested: MutableMap<String, Map<String, Boolean>> = mutableMapOf()
            ) {
                public fun handle(): Unit = Unit
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "JavaMapCommand.java",
            """
            package map.fixtures;

            import io.cratis.arc.artifacts.Command;
            import java.util.List;
            import java.util.Map;

            @Command
            public record JavaMapCommand(
                Map<String, String> strings,
                Map<String, List<Integer>> numbers,
                Map<String, Map<String, Boolean>> nested
            ) {
                public JavaMapCommand() {
                    this(Map.of(), Map.of(), Map.of());
                }

                public void handle() {
                }
            }
            """.trimIndent()
        )
    )

    private fun outerNullableJavaMapSources(): List<SourceFile> = listOf(
        SourceFile.kotlin("OuterNullableAnchor.kt", "package javamap.outer\n\ninternal object OuterNullableAnchor"),
        javaMapCommandSource("javamap.outer", "OuterNullableMapCommand", "@Nullable Map<String, String>")
    )

    private fun invalidJavaMapShapeSources(): List<SourceFile> = listOf(
        javaMapCommandSource("javamap.invalid", "NullableKeyMapCommand", "Map<@Nullable String, String>"),
        javaMapCommandSource("javamap.invalid", "NullableValueMapCommand", "Map<String, @Nullable String>"),
        javaMapCommandSource(
            "javamap.invalid",
            "NullableListElementMapCommand",
            "Map<String, List<@Nullable String>>"
        ),
        javaMapCommandSource(
            "javamap.invalid",
            "NullableNestedMapValueCommand",
            "Map<String, Map<String, @Nullable String>>"
        ),
        javaMapCommandSource("javamap.invalid", "RawMapCommand", "Map"),
        javaMapCommandSource("javamap.invalid", "WildcardMapCommand", "Map<String, ?>")
    )

    private fun javaMapCommandSource(packageName: String, className: String, typeName: String): SourceFile =
        SourceFile.java(
            "$className.java",
            """
            package $packageName;

            import io.cratis.arc.artifacts.Command;
            import java.util.List;
            import java.util.Map;
            import org.jetbrains.annotations.Nullable;

            @Command
            public record $className($typeName values) {
                public void handle() {
                }
            }
            """.trimIndent()
        )

    private fun negativeSources(): List<SourceFile> = listOf(
        SourceFile.kotlin(
            "InvalidMaps.kt",
            """
            package map.invalid

            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.ReadModel
            import io.cratis.arc.concepts.ConceptAs
            import io.cratis.arc.artifacts.FromServices
            import io.cratis.arc.queries.QueryHttpMethod
            import kotlinx.coroutines.flow.Flow
            import java.time.Instant
            import java.util.UUID

            public data class ModelLeaf(public val value: String)
            public enum class EnumLeaf { Value }
            public data class MapConcept(private val raw: Map<String, String>) : ConceptAs<Map<String, String>> {
                override fun value(): Map<String, String> = raw
            }

            @Command public data class BadKey(public val values: Map<Int, String>) { public fun handle() {} }
            @Command public data class NullableValue(public val values: Map<String, String?>) { public fun handle() {} }
            @Command public data class NullableElement(public val values: Map<String, List<Int?>>) { public fun handle() {} }
            @Command public data class FloatValue(public val values: Map<String, Float>) { public fun handle() {} }
            @Command public data class DoubleValue(public val values: Map<String, Double>) { public fun handle() {} }
            @Command public data class ModelValue(public val values: Map<String, ModelLeaf>) { public fun handle() {} }
            @Command public data class ConceptValue(public val values: Map<String, MapConcept>) { public fun handle() {} }
            @Command public data class EnumValue(public val values: Map<String, EnumLeaf>) { public fun handle() {} }
            @Command public data class UuidValue(public val values: Map<String, UUID>) { public fun handle() {} }
            @Command public data class TemporalValue(public val values: Map<String, Instant>) { public fun handle() {} }
            @Command public data class StarMap(public val values: Map<*, String>) { public fun handle() {} }
            @Command public data class ConceptMap(public val value: MapConcept) { public fun handle() {} }
            @Command public class MapResponse { public fun handle(): Map<String, String> = emptyMap() }
            @Command public class MapService { public fun handle(values: Map<String, String>) {} }

            @ReadModel public data class QueryMaps(public val value: String) {
                public companion object {
                    public fun parameter(values: Map<String, String>): QueryMaps = QueryMaps(values.size.toString())
                    public fun service(@FromServices values: Map<String, String>): QueryMaps = QueryMaps(values.size.toString())
                    public fun observableParameter(values: Flow<Map<String, String>>): QueryMaps = QueryMaps("value")
                    @QueryHttpMethod public fun returns(): Map<String, String> = emptyMap()
                    @QueryHttpMethod public fun observableReturn(): Flow<Map<String, String>> = error("not invoked")
                }
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "PrimitiveFloatJavaMapCommand.java",
            """
            package map.invalid;

            import io.cratis.arc.artifacts.Command;
            import java.util.Map;

            @Command
            public record PrimitiveFloatJavaMapCommand(Map<String, float[]> values) {
                public void handle() {
                }
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "PrimitiveDoubleJavaMapCommand.java",
            """
            package map.invalid;

            import io.cratis.arc.artifacts.Command;
            import java.util.Map;

            @Command
            public record PrimitiveDoubleJavaMapCommand(Map<String, double[]> values) {
                public void handle() {
                }
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "BoxedFloatJavaMapCommand.java",
            """
            package map.invalid;

            import io.cratis.arc.artifacts.Command;
            import java.util.Map;

            @Command
            public record BoxedFloatJavaMapCommand(Map<String, Float> values) {
                public void handle() {
                }
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "BoxedDoubleJavaMapCommand.java",
            """
            package map.invalid;

            import io.cratis.arc.artifacts.Command;
            import java.util.Map;

            @Command
            public record BoxedDoubleJavaMapCommand(Map<String, Double> values) {
                public void handle() {
                }
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "RawJavaMapCommand.java",
            """
            package map.invalid;

            import io.cratis.arc.artifacts.Command;
            import java.util.Map;

            @Command
            public record RawJavaMapCommand(Map values) {
                public void handle() {
                }
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "WildcardJavaMapCommand.java",
            """
            package map.invalid;

            import io.cratis.arc.artifacts.Command;
            import java.util.Map;

            @Command
            public record WildcardJavaMapCommand(Map<String, ?> values) {
                public void handle() {
                }
            }
            """.trimIndent()
        )
    )

    private fun module(result: JvmCompilationResult): ArcArtifactModule = result.classLoader
        .loadClass("io.cratis.arc.generated.MapMetadataArcArtifactModule")
        .getDeclaredConstructor()
        .newInstance() as ArcArtifactModule

    private fun compile(sources: List<SourceFile>, workingDirectory: File? = null): JvmCompilationResult =
        KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            workingDirectory?.let { directory -> workingDir = directory }
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "MapMetadata")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
}
