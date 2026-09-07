// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.metadata.DocumentationSummaries
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorDocumentationCompilationTest {
    @Test
    fun `Kotlin and Java documentation reaches generated metadata and the manifest deterministically`() {
        val firstDirectory = Files.createTempDirectory("arc-documentation-first").toFile()
        val secondDirectory = Files.createTempDirectory("arc-documentation-second").toFile()
        val first = compile(documentedSources(), firstDirectory)
        val second = compile(documentedSources(), secondDirectory)

        assertEquals(KotlinCompilation.ExitCode.OK, first.exitCode, first.messages)
        assertEquals(KotlinCompilation.ExitCode.OK, second.exitCode, second.messages)
        assertTrue(
            manifestBytes(firstDirectory).contentEquals(manifestBytes(secondDirectory)),
            "Documented manifests must be byte identical across identical compilations."
        )

        val manifest = manifest(firstDirectory)
        val command = manifest.named("commands", "CreateDocumented")
        assertEquals("Creates a documented fixture.", command.summary())
        assertEquals("Documented command key.", command.property("id").summary())
        assertEquals("Documented through a property tag.", command.property("label").summary())
        assertNull(command.property("undocumented").summary())

        val model = manifest.named("types", "DocumentedModel")
        assertEquals("Documented Kotlin model.", model.summary())
        assertEquals("Documented Kotlin model value.", model.property("value").summary())

        val record = manifest.named("types", "DocumentedRecord")
        assertEquals("Documented Java record.", record.summary())
        assertEquals("Documented Java record component.", record.property("code").summary())

        val contract = manifest.named("interfaces", "DocumentedContract")
        assertEquals("Documented Kotlin interface.", contract.summary())
        assertEquals("Documented Kotlin interface property.", contract.property("name").summary())

        val enum = manifest.named("enums", "DocumentedState")
        assertEquals("Documented Kotlin enum.", enum.summary())

        val query = manifest.named("queries", "find")
        assertEquals("Finds a documented model.", query.summary())
        assertEquals("Documented client filter that costs \u0024" + "5.", query.parameter("filter").summary())
    }

    @Test
    fun `generated Kotlin carries documentation that would otherwise break a string literal`() {
        val directory = Files.createTempDirectory("arc-documentation-generated").toFile()
        val result = compile(documentedSources(), directory)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val handler = generatedSources(directory, "commands").values.single()
        assertTrue(
            "summary = \"Creates a documented fixture.\"" in handler,
            "The generated handler must carry the command summary:\n$handler"
        )
        assertTrue("summary = \"Documented command key.\"" in handler, handler)
        val performer = generatedSources(directory, "queries").values.single()
        assertTrue("summary = \"Finds a documented model.\"" in performer, performer)
        assertTrue("Documented client filter that costs" in performer, performer)
        // The dollar sign must be escaped, or the generated Kotlin would not have compiled at all.
        assertTrue("costs \\\$5" in performer, performer)
    }

    @Test
    fun `excluded documentation never reaches metadata`() {
        val directory = Files.createTempDirectory("arc-documentation-excluded").toFile()
        val result = compile(excludedSources(), directory)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val manifest = manifest(directory)

        val enum = manifest.named("enums", "ExcludedState")
        assertEquals("Documented enum whose members are never documented.", enum.summary())
        assertTrue(enum.path("members").all { member -> member.get("summary") == null })

        val command = manifest.named("commands", "CreateExcluded")
        assertEquals("Only the first paragraph is a summary.", command.summary())
        assertFalse(manifestJson(directory).contains("second paragraph"))
        // A Kotlin class documents properties with `@property`, so a constructor `@param` tag documents nothing.
        assertNull(command.property("id").summary())

        val query = manifest.named("queries", "excluded")
        assertEquals("Summary that follows a leading code block.", query.summary())
        assertFalse(manifestJson(directory).contains("fenced code"))
        query.path("parameters").forEach { parameter ->
            if (parameter.get("source").asText() != "CLIENT") {
                assertNull(parameter.get("summary"), "Only a client query parameter may carry documentation.")
            }
        }
        assertEquals("Documented client argument.", query.parameter("filter").summary())
        assertNull(query.parameter("dependency").summary())

        val model = manifest.named("types", "ExcludedModel")
        val truncated = requireNotNull(model.property("value").summary())
        assertEquals(DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS, truncated.codePointCount(0, truncated.length))
        assertEquals(truncated, DocumentationSummaries.validate(truncated, "value"))
    }

    private fun JsonNode.summary(): String? = get("summary")?.textValue()

    private fun JsonNode.named(collection: String, name: String): JsonNode =
        path(collection).single { node -> node.get("name").asText() == name }

    private fun JsonNode.property(name: String): JsonNode =
        path("properties").single { node -> node.get("name").asText() == name }

    private fun JsonNode.parameter(name: String): JsonNode =
        path("parameters").single { node -> node.get("name").asText() == name }

    private fun manifest(directory: File): JsonNode = ObjectMapper().readTree(manifestBytes(directory))

    private fun manifestJson(directory: File): String = manifestBytes(directory).toString(Charsets.UTF_8)

    private fun manifestBytes(directory: File): ByteArray =
        directory.resolve("ksp/sources/resources/META-INF/cratis/arc/Documented.json").readBytes()

    private fun generatedSources(directory: File, kind: String): Map<String, String> {
        val root = directory.toPath().resolve("ksp/sources/kotlin/io/cratis/arc/generated/$kind")
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .sorted()
                .iterator()
                .asSequence()
                .associate { path -> path.fileName.toString() to Files.readString(path) }
        }
    }

    private fun documentedSources(): List<SourceFile> = listOf(
        SourceFile.kotlin(
            "DocumentedFixtures.kt",
            """
            package documented.fixtures

            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.CommandKey
            import io.cratis.arc.artifacts.ReadModel

            /** Documented Kotlin enum. */
            public enum class DocumentedState {
                /** Never collected. */
                FIRST,

                /** Never collected either. */
                SECOND
            }

            /** Documented Kotlin interface. */
            public interface DocumentedContract {
                /** Documented Kotlin interface property. */
                public val name: String
            }

            /** Documented Kotlin model. */
            public data class DocumentedModel(
                /** Documented Kotlin model value. */
                public val value: String
            )

            /**
             * Creates a documented fixture.
             *
             * @property label Documented through a property tag.
             */
            @Command
            public data class CreateDocumented(
                /** Documented command key. */
                @CommandKey public val id: String,
                public val label: String,
                public val undocumented: String
            ) {
                public fun handle(): String = id
            }

            /** Documented Kotlin read model. */
            @ReadModel
            public data class DocumentedReadModel(
                public val model: DocumentedModel,
                public val state: DocumentedState,
                public val contract: DocumentedContract,
                public val record: DocumentedRecord
            ) {
                public companion object {
                    /**
                     * Finds a documented model.
                     *
                     * @param filter Documented client filter that costs ${'$'}5.
                     */
                    public fun find(filter: String): DocumentedReadModel = DocumentedReadModel(
                        DocumentedModel(filter),
                        DocumentedState.FIRST,
                        DocumentedImplementation(filter),
                        DocumentedRecord(filter)
                    )
                }
            }

            /** Documented Kotlin implementation. */
            public data class DocumentedImplementation(override val name: String) : DocumentedContract
            """.trimIndent()
        ),
        SourceFile.java(
            "DocumentedRecord.java",
            """
            package documented.fixtures;

            /**
             * Documented Java record.
             *
             * @param code Documented Java record component.
             */
            public record DocumentedRecord(String code) {
            }
            """.trimIndent()
        )
    )

    private fun excludedSources(): List<SourceFile> = listOf(
        SourceFile.kotlin(
            "ExcludedFixtures.kt",
            """
            package documented.fixtures

            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.CommandKey
            import io.cratis.arc.artifacts.FromServices
            import io.cratis.arc.artifacts.ReadModel

            /** Documented enum whose members are never documented. */
            public enum class ExcludedState {
                /** Never collected. */
                ONLY
            }

            public class ExcludedDependency

            /** Documented Kotlin model. */
            public data class ExcludedModel(
                /** ${"long ".repeat(200)} */
                public val value: String
            )

            /**
             * Only the first paragraph is a summary.
             *
             * This second paragraph must never be captured.
             *
             * @param id Constructor parameter documentation that a Kotlin class never projects onto a property.
             */
            @Command
            public data class CreateExcluded(@CommandKey public val id: String) {
                public fun handle(): String = id
            }

            /** Documented Kotlin read model. */
            @ReadModel
            public data class ExcludedReadModel(
                public val model: ExcludedModel,
                public val state: ExcludedState
            ) {
                public companion object {
                    /**
                     * ```
                     * a fenced code block
                     * ```
                     * Summary that follows a leading code block.
                     *
                     * @param filter Documented client argument.
                     * @param dependency Service documentation that never reaches a proxy.
                     */
                    public fun excluded(
                        filter: String,
                        @FromServices dependency: ExcludedDependency
                    ): ExcludedReadModel {
                        dependency.hashCode()
                        return ExcludedReadModel(ExcludedModel(filter), ExcludedState.ONLY)
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
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "Documented")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
}
