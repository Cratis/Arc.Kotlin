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
import io.cratis.arc.metadata.QueryParameterSource
import java.io.File
import java.nio.file.Files
import kotlin.io.path.extension
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorSpringDataCompilationTest {
    @Test
    fun `exact Spring Data adapters and pages generate direct request-owned code`() {
        val directory = Files.createTempDirectory("arc-spring-data-query").toFile()
        val result = compile(springDataSources(), directory)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val generated = generatedQuerySources(directory)
        assertEquals(3, generated.size)
        val combined = generated.values.joinToString("\n")
        assertTrue("org.springframework.data.domain.Pageable.unpaged(" in combined)
        assertTrue("org.springframework.data.domain.PageRequest.of(" in combined)
        assertTrue("org.springframework.data.domain.Sort.unsorted()" in combined)
        assertTrue("org.springframework.data.domain.Sort.Direction.DESC" in combined)
        assertTrue("io.cratis.arc.queries.QueryPage(" in combined)
        assertTrue("_page.content" in combined)
        assertTrue("_page.totalElements" in combined)
        assertTrue(generated.values.any { source -> ".await()).let { _page ->" in source })
        val kotlinParameters = mapOf(
            "label" to QueryParameterSource.CLIENT,
            "pageable" to QueryParameterSource.HOST_ADAPTER,
            "request" to QueryParameterSource.QUERY_REQUEST,
            "sort" to QueryParameterSource.HOST_ADAPTER
        )
        val expectedParameters = mapOf(
            "springdata.fixtures.KotlinSpringDataReadModel.direct" to kotlinParameters,
            "springdata.fixtures.KotlinSpringDataReadModel.suspended" to kotlinParameters,
            "springdata.fixtures.JavaSpringDataReadModel.asynchronous" to mapOf(
                "pageable" to QueryParameterSource.HOST_ADAPTER,
                "label" to QueryParameterSource.CLIENT,
                "sort" to QueryParameterSource.HOST_ADAPTER
            )
        )
        val module = result.classLoader.loadClass("io.cratis.arc.generated.SpringDataQueriesArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        assertEquals(3, module.queryPerformers.size)
        assertEquals(expectedParameters.keys, module.queryPerformers.map { it.descriptor.fullyQualifiedName }.toSet())
        assertEquals(expectedParameters.keys.map { "${queryPerformerClassName(it)}.kt" }.toSet(), generated.keys)
        module.queryPerformers.forEach { performer ->
            val descriptor = performer.descriptor
            val queryName = descriptor.fullyQualifiedName
            assertEquals("io.cratis.arc.generated.queries.${queryPerformerClassName(queryName)}", performer.javaClass.name)
            assertEquals(expectedParameters.getValue(queryName), descriptor.parameters.associate { it.name to it.source })
            assertTrue(descriptor.supportsPaging, queryName)
            assertTrue(descriptor.supportsSorting, queryName)
        }
        listOf("JpaQueryPageAdapter", "MongoQueryPageAdapter", "ThreadLocal", "serviceResolver.require").forEach { forbidden ->
            assertFalse(forbidden in combined, "Generated Spring Data performers must not contain '$forbidden':\n$combined")
        }
    }

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

    private fun springDataSources(): List<SourceFile> = listOf(
        SourceFile.kotlin(
            "KotlinSpringDataReadModel.kt",
            """
            package springdata.fixtures

            import io.cratis.arc.artifacts.ReadModel
            import io.cratis.arc.queries.QueryRequest
            import org.springframework.data.domain.Page
            import org.springframework.data.domain.PageImpl
            import org.springframework.data.domain.Pageable
            import org.springframework.data.domain.Sort

            @ReadModel
            public data class KotlinSpringDataReadModel(public val value: String) {
                public companion object {
                    public fun direct(
                        label: String,
                        pageable: Pageable,
                        request: QueryRequest,
                        sort: Sort
                    ): Page<KotlinSpringDataReadModel> {
                        require(pageable.sort == sort)
                        return PageImpl(listOf(KotlinSpringDataReadModel(label)), pageable, 11)
                    }

                    public suspend fun suspended(
                        sort: Sort,
                        request: QueryRequest,
                        pageable: Pageable,
                        label: String
                    ): Page<KotlinSpringDataReadModel> {
                        require(pageable.sort == sort)
                        return PageImpl(listOf(KotlinSpringDataReadModel(label)), pageable, 13)
                    }
                }
            }
            """.trimIndent()
        ),
        SourceFile.java(
            "JavaSpringDataReadModel.java",
            """
            package springdata.fixtures;

            import io.cratis.arc.artifacts.ReadModel;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.CompletionStage;
            import org.springframework.data.domain.Page;
            import org.springframework.data.domain.PageImpl;
            import org.springframework.data.domain.Pageable;
            import org.springframework.data.domain.Sort;

            @ReadModel
            public record JavaSpringDataReadModel(String value) {
                public static CompletionStage<Page<JavaSpringDataReadModel>> asynchronous(
                    Pageable pageable,
                    String label,
                    Sort sort
                ) {
                    return CompletableFuture.completedFuture(
                        new PageImpl<>(List.of(new JavaSpringDataReadModel(label)), pageable, 17)
                    );
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
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "SpringDataQueries")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
}
