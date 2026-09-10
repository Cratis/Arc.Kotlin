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
import io.cratis.arc.json.ArcObjectMapper
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorCommandEventMetadataCompilationTest {
    @Test
    fun `Kotlin and Java command event defaults are emitted as typed manifest metadata`() {
        val workingDirectory = Files.createTempDirectory("arc-command-event-metadata").toFile()
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "KotlinCommand.kt",
                    """
                    package event.metadata

                    import io.cratis.arc.artifacts.Command
                    import io.cratis.arc.artifacts.CommandEventSourceType
                    import io.cratis.arc.artifacts.CommandEventStreamId
                    import io.cratis.arc.artifacts.CommandEventStreamType
                    import io.cratis.arc.artifacts.CommandEventSubject

                    @Command
                    @CommandEventSourceType("Order")
                    @CommandEventStreamType("Orders")
                    @CommandEventStreamId("priority")
                    @CommandEventSubject("sales")
                    public class KotlinCommand {
                        public fun handle(): Unit = Unit
                    }
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaCommand.java",
                    """
                    package event.metadata;

                    import io.cratis.arc.artifacts.Command;
                    import io.cratis.arc.artifacts.CommandEventStreamId;
                    import io.cratis.arc.artifacts.CommandEventSubject;

                    @Command
                    @CommandEventStreamId("java-stream")
                    @CommandEventSubject("java-subject")
                    public class JavaCommand {
                        public void handle() {}
                    }
                    """.trimIndent()
                )
            ),
            workingDirectory
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val manifestFile = workingDirectory.resolve(
            "ksp/sources/resources/META-INF/cratis/arc/EventMetadata.json"
        )
        val commands = ArcObjectMapper.create().readTree(manifestFile).path("commands").values()
            .associateBy { command -> command.path("name").asString() }
        val kotlin = commands.getValue("KotlinCommand").path("eventMetadata")
        assertEquals("Order", kotlin.path("eventSourceType").asString())
        assertEquals("Orders", kotlin.path("eventStreamType").asString())
        assertEquals("priority", kotlin.path("eventStreamId").asString())
        assertEquals("sales", kotlin.path("subject").asString())
        val java = commands.getValue("JavaCommand").path("eventMetadata")
        assertEquals("java-stream", java.path("eventStreamId").asString())
        assertEquals("java-subject", java.path("subject").asString())
        assertFalse(java.has("eventSourceType"))
        assertFalse(java.has("eventStreamType"))
    }

    private fun compile(sources: List<SourceFile>, workingDirectory: File): JvmCompilationResult =
        KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            workingDir = workingDirectory
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "EventMetadata")
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
}
