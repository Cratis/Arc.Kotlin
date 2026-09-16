// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.CommandResponseValueDisposition.HANDLED
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorDependencyHandlersCompilationTest {
    @Test
    fun `handler only producer exports declarations without an artifact module`() {
        val producer = producer()
        val result = producer.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(resource(producer).isFile, "Handler-only producer must export validated local declarations")
        assertFalse(producer.workingDir.resolve("ksp/sources/resources/META-INF/cratis/arc/Producer.json").exists())
        assertEquals(document + "\n", resource(producer).readText())
    }

    @Test
    fun `consumer resolves declarations from binary classes without source discovery`() {
        val producer = producer()
        val produced = producer.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, produced.exitCode, produced.messages)
        // The new transport is supplied explicitly; old artifact metadata never claimed this capability.
        val index = producer.workingDir.resolve("handler index.json")
        index.writeText("{\"formatVersion\":1,\"modules\":[$document]}")
        val consumer = compilation("Consumer", listOf(SourceFile.kotlin("Save.kt", """
            package consumer
            @io.cratis.arc.artifacts.Command
            public class Save { public fun handle(): producer.Payload = producer.Payload("server") }
        """.trimIndent()))).apply {
            classpaths = listOf(produced.outputDirectory)
            kspProcessorOptions["arc.responseHandlerMetadata"] = index.toURI().toASCIIString()
        }
        val consumed = consumer.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, consumed.exitCode, consumed.messages)
        val module = consumed.classLoader.loadClass("io.cratis.arc.generated.ConsumerArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        assertEquals(listOf(HANDLED), module.commandHandlers.single().metadata.responseValues.map { it.disposition })
        assertEquals(listOf("consumer.Save"), module.types.map { it.fullyQualifiedName })
        assertFalse(resource(consumer).exists(), "Imported declarations must not be re-exported")
    }

    @Test
    fun `unreadable metadata option reports configuration diagnostic without aggregate output`() {
        val consumer = compilation("Consumer", listOf(SourceFile.kotlin("Save.kt", """
            package consumer
            @io.cratis.arc.artifacts.Command public class Save { public fun handle(): Unit = Unit }
        """.trimIndent())))
        consumer.kspProcessorOptions["arc.responseHandlerMetadata"] = "relative-index.json"
        val result = consumer.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0001]" in result.messages, result.messages)
        assertFalse(consumer.workingDir.resolve("ksp/sources/resources/META-INF/cratis/arc/Consumer.json").exists())
    }

    @Test
    fun `missing binary classes and annotation disagreement name the resource and fail closed`() {
        val produced = producer().compile()
        assertEquals(KotlinCompilation.ExitCode.OK, produced.exitCode, produced.messages)
        for (declaration in listOf(
            document.replace("producer.Handler", "producer.Missing"),
            document.replace("producer.Payload", "kotlin.String"),
            document.replace("producer.Handler", "producer.Payload")
        )) {
            val consumer = compilation("Consumer", listOf(SourceFile.kotlin("Save.kt", """
                package consumer
                @io.cratis.arc.artifacts.Command public class Save { public fun handle(): producer.Payload = producer.Payload("server") }
            """.trimIndent())))
            val index = consumer.workingDir.resolve("index.json")
            index.writeText("{\"formatVersion\":1,\"modules\":[$declaration]}")
            consumer.classpaths = listOf(produced.outputDirectory)
            consumer.kspProcessorOptions["arc.responseHandlerMetadata"] = index.toURI().toASCIIString()
            val result = consumer.compile()
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue("[ARCKSP0102]" in result.messages, result.messages)
            assertTrue("META-INF/cratis/arc-response-handlers/Producer.json" in result.messages, result.messages)
            assertFalse(consumer.workingDir.resolve("ksp/sources/resources/META-INF/cratis/arc/Consumer.json").exists())
        }
    }

    @Test
    fun `binary handler supertype matching does not classify collections of its values`() {
        val produced = producer().compile()
        assertEquals(KotlinCompilation.ExitCode.OK, produced.exitCode, produced.messages)
        val consumer = compilation("Consumer", listOf(SourceFile.kotlin("Shapes.kt", """
            package consumer
            public class Child : producer.Payload("child")
            @io.cratis.arc.artifacts.Command public class Scalar { public fun handle(): Child = Child() }
            @io.cratis.arc.artifacts.Command public class Collection { public fun handle(): List<producer.Payload> = emptyList() }
        """.trimIndent())))
        val index = consumer.workingDir.resolve("index.json")
        index.writeText("{\"formatVersion\":1,\"modules\":[$document]}")
        consumer.classpaths = listOf(produced.outputDirectory)
        consumer.kspProcessorOptions["arc.responseHandlerMetadata"] = index.toURI().toASCIIString()
        val result = consumer.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.ConsumerArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        assertEquals(listOf(HANDLED), module.commandHandlers.single { it.metadata.name == "Scalar" }.metadata.responseValues.map { it.disposition })
        val collection = module.commandHandlers.single { it.metadata.name == "Collection" }.metadata
        assertEquals(listOf(io.cratis.arc.metadata.CommandResponseValueDisposition.CLIENT), collection.responseValues.map { it.disposition })
        assertTrue(collection.responseIsEnumerable)
        assertTrue(module.types.any { it.fullyQualifiedName == "producer.Payload" })
        assertFalse(module.types.any { it.fullyQualifiedName == "consumer.Child" })
    }

    private fun producer(): KotlinCompilation = compilation("Producer", listOf(SourceFile.kotlin("Handler.kt", """
        package producer
        public open class Payload(public val value: String)
        @io.cratis.arc.commands.HandlesCommandResponseValues(Payload::class)
        public class Handler : io.cratis.arc.java.BlockingCommandResponseValueHandler {
            override fun canHandle(context: io.cratis.arc.commands.CommandContext, value: Any): Boolean = value is Payload
            override fun handle(context: io.cratis.arc.commands.CommandContext, value: Any): io.cratis.arc.results.CommandResult<*> =
                io.cratis.arc.results.CommandResult.success(context.correlationId)
        }
    """.trimIndent())))

    private fun compilation(module: String, sources: List<SourceFile>): KotlinCompilation = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources
        inheritClassPath = true
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to module)
        kspWithCompilation = true
        messageOutputStream = System.out
    }

    private fun resource(compilation: KotlinCompilation): File = compilation.workingDir.resolve(
        "ksp/sources/resources/META-INF/cratis/arc-response-handlers/${compilation.kspProcessorOptions["arc.moduleName"]}.json")

    private val document = "{\"formatVersion\":1,\"moduleName\":\"Producer\",\"handlers\":[{\"handlerTypeName\":\"producer.Handler\",\"handledTypeNames\":[\"producer.Payload\"]}]}"
}
