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
import java.io.File
import java.nio.file.Files
import kotlin.io.path.extension
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorQueryDefaultsCompilationTest {
    @Test
    fun `Kotlin query defaults generate deterministic exhaustive named invocation branches`() {
        val sources = defaultQuerySources()
        val firstDirectory = Files.createTempDirectory("arc-query-defaults-first").toFile()
        val secondDirectory = Files.createTempDirectory("arc-query-defaults-second").toFile()
        val first = compile(sources, firstDirectory)
        val second = compile(sources, secondDirectory)

        assertEquals(KotlinCompilation.ExitCode.OK, first.exitCode, first.messages)
        assertEquals(KotlinCompilation.ExitCode.OK, second.exitCode, second.messages)
        val firstSources = generatedQuerySources(firstDirectory)
        val secondSources = generatedQuerySources(secondDirectory)
        assertEquals(firstSources, secondSources)
        assertEquals(3, firstSources.size)
        assertTrue(
            firstDirectory.resolve("ksp/sources/resources/META-INF/cratis/arc/QueryDefaults.json").readBytes()
                .contentEquals(
                    secondDirectory.resolve("ksp/sources/resources/META-INF/cratis/arc/QueryDefaults.json").readBytes()
                )
        )

        val direct = firstSources.values.single { source -> "name = \"direct\"" in source }
        val suspended = firstSources.values.single { source -> "name = \"suspended\"" in source }
        val observable = firstSources.values.single { source -> "name = \"observable\"" in source }

        assertEquals(8, branchCount(direct))
        assertEquals(2, branchCount(suspended))
        assertEquals(2, branchCount(observable))
        listOf("prefix", "suffix", "count").forEach { name ->
            assertTrue("context.request.arguments.containsKey(\"$name\")" in direct)
        }
        assertTrue("required = _argument0" in direct)
        assertTrue("request = context.request" in direct)
        assertTrue("dependency = context.serviceResolver.require(" in direct)
        assertTrue("context = context" in direct)
        assertTrue("prefix = _resolveArgument1()" in direct)
        assertTrue("suffix = _resolveArgument3()" in direct)
        assertTrue("count = _resolveArgument5()" in direct)
        assertTrue("hasDefault = true" in direct)
        assertTrue("QueryHttpMethodType.GET" in direct)
        assertTrue("QueryHttpMethodType.QUERY" in suspended)
        assertTrue("QueryHttpMethodType.QUERY" in observable)
        listOf("callBy", "java.lang.reflect", "kotlin.reflect", "\$default", "prefix-default", "-suffix").forEach { forbidden ->
            assertFalse(forbidden in direct, "Generated performer must not contain '$forbidden':\n$direct")
        }
    }

    @Test
    fun `annotated helper with an invalid query return remains a compilation error`() {
        val directory = Files.createTempDirectory("arc-invalid-annotated-helper").toFile()
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "InvalidHelper.kt",
                    """
                    package defaults.fixtures

                    import io.cratis.arc.artifacts.ReadModel
                    import io.cratis.arc.queries.QueryHttpMethod

                    @ReadModel
                    public data class InvalidHelper(public val value: String) {
                        public companion object {
                            @QueryHttpMethod
                            public fun helper(): String = "invalid"
                        }
                    }
                    """.trimIndent()
                )
            ),
            directory
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("does not match annotated read model" in result.messages, result.messages)
    }

    private fun branchCount(source: String): Int = Regex("(?m)^ {12}\\d+ ->").findAll(source).count()

    private fun generatedQuerySources(directory: File): Map<String, String> {
        val root = directory.toPath().resolve("ksp/sources/kotlin/io/cratis/arc/generated/queries")
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path -> path.extension == "kt" }
                .sorted()
                .iterator()
                .asSequence()
                .associate { path -> path.fileName.toString() to Files.readString(path) }
        }
    }

    private fun defaultQuerySources(): List<SourceFile> = listOf(
        SourceFile.kotlin(
            "DefaultQueries.kt",
            """
            package defaults.fixtures

            import io.cratis.arc.artifacts.FromServices
            import io.cratis.arc.artifacts.ReadModel
            import io.cratis.arc.queries.QueryContext
            import io.cratis.arc.queries.QueryHttpMethod
            import io.cratis.arc.queries.QueryHttpMethodType
            import io.cratis.arc.queries.QueryRequest
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flowOf

            public class Dependency

            @ReadModel
            @QueryHttpMethod(QueryHttpMethodType.QUERY)
            public data class DefaultsReadModel(public val value: String) {
                public companion object {
                    public fun helper(): String = "ignored"

                    @QueryHttpMethod(QueryHttpMethodType.GET)
                    public fun direct(
                        required: String,
                        prefix: String = "prefix-default",
                        request: QueryRequest,
                        suffix: String = "${'$'}prefix-suffix",
                        @FromServices dependency: Dependency,
                        count: Int = 2,
                        context: QueryContext
                    ): DefaultsReadModel {
                        require(context.request === request)
                        dependency.hashCode()
                        return DefaultsReadModel("${'$'}required|${'$'}prefix|${'$'}suffix|${'$'}count")
                    }

                    public suspend fun suspended(
                        request: QueryRequest,
                        label: String = "suspend-default",
                        context: QueryContext
                    ): DefaultsReadModel {
                        require(context.request === request)
                        return DefaultsReadModel(label)
                    }

                    public fun observable(
                        context: QueryContext,
                        label: String = "flow-default",
                        request: QueryRequest
                    ): Flow<DefaultsReadModel> {
                        require(context.request === request)
                        return flowOf(DefaultsReadModel(label))
                    }
                }
            }
            """.trimIndent()
        )
    )

    private fun compile(sources: List<SourceFile>, workingDirectory: File): JvmCompilationResult =
        KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            workingDir = workingDirectory
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "QueryDefaults")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
}
