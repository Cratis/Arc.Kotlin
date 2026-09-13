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
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorVisibilityCompilationTest {
    @TempDir lateinit var work: File

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun `implicit classes members and all implicit consumers match explicit identities and execute generated invocations`(variant: Int): Unit = runBlocking {
        var expected: Map<String, String>? = null
        val variants = listOf("public " to "public ", "" to "public ", "public " to "", "" to "")
        for (index in listOf(0, variant)) {
            val visibility = variants[index]
            val (classes, members) = visibility
            val directory = work.resolve("positive-$index")
            val result = compile(directory, SourceFile.kotlin("Visibility.kt", """
                package visibility
                ${classes}interface Named { ${members}val value: String }
                ${classes}class Child(${members}val first: String) { ${members}var last: String = "last" }
                @io.cratis.arc.artifacts.Command
                ${classes}class Input(${members}override val value: String) : Named {
                    @io.cratis.arc.artifacts.CommandKey ${members}var id: String = "key"
                    @get:jakarta.validation.constraints.Size(min = 2, max = 30)
                    ${members}var title: String = "title"
                        private set
                    @field:jakarta.validation.Valid ${members}var child: Child = Child("first")
                    private val secret: String = "secret"
                    internal val internalState: String = "internal"
                    protected val protectedState: String = "protected"
                    ${members}fun provide(): String = value + title
                    ${members}fun handle(provided: String): String = provided + id + child.last
                }
                @io.cratis.arc.artifacts.ReadModel
                ${classes}data class View(${members}override val value: String) : Named {
                    ${members}companion object {
                        ${members}fun all(prefix: String): List<View> = listOf(View(prefix))
                        private fun helper(): String = "unrelated"
                    }
                }
                @io.cratis.arc.artifacts.ReadModel
                ${classes}class NoQueries(${members}val value: String) {
                    private companion object { fun helper(): String = "unrelated" }
                }
            """.trimIndent()))
            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
            val module = result.classLoader.loadClass("io.cratis.arc.generated.VisibilityArcArtifactModule")
                .getDeclaredConstructor().newInstance() as ArcArtifactModule
            val handler = module.commandHandlers.single()
            val properties = handler.metadata.properties
            assertEquals(listOf("value", "child", "id", "title"), properties.map { it.name })
            assertEquals(properties, module.types.single { it.name == "Input" }.properties)
            assertEquals(listOf("first", "last"), module.types.single { it.name == "Child" }.properties.map { it.name })
            assertEquals(listOf("value"), module.interfaces.single().properties.map { it.name })
            assertEquals(listOf("value"), module.types.single { it.name == "View" }.properties.map { it.name })
            assertTrue(properties.single { it.name == "child" }.validateRecursively)
            assertEquals(listOf("length"), properties.single { it.name == "title" }.validationRules.map { it.ruleName })
            assertTrue(properties.single { it.name == "id" }.isCommandKey)
            val command = ArcObjectMapper.create().readValue("""{"value":"v","id":"supplied","title":"changed"}""", handler.commandType)
            assertEquals("supplied", handler.resolveCommandKey(command))
            val services = object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
            val commands = ConcurrentCommandHandlerRegistry().also { it.register(handler) }
            val response = DefaultCommandPipeline(commands).execute(command, CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal(), services))
            assertTrue(response.isSuccess)
            assertEquals("vchangedsuppliedlast", response.response)
            val performer = module.queryPerformers.single()
            assertEquals(listOf("prefix"), performer.descriptor.parameters.map { it.name })
            val queries = ConcurrentQueryPerformerRegistry().also { it.register(performer) }
            val query = DefaultQueryPipeline(queries).perform(QueryRequest(performer.fullyQualifiedName, mapOf("prefix" to "query")),
                QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal(), services))
            assertTrue(query.isSuccess)
            assertEquals("""[{"value":"query"}]""", ArcObjectMapper.create().writeValueAsString(query.data))
            val generated = directory.resolve("ksp/sources").walkTopDown().filter(File::isFile)
                .associate { it.relativeTo(directory.resolve("ksp/sources")).invariantSeparatorsPath to it.readText() }
            assertTrue(generated.keys.any { it.endsWith("Visibility.json") })
            assertTrue(generated.values.any { it.contains("typedCommand.id") })
            assertTrue(generated.values.any { it.contains("public class") })
            if (expected == null) expected = generated else assertEquals(expected, generated, "Same identities must produce identical sources and manifest")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["private", "internal"])
    fun `nonpublic artifact classes and query companions remain rejected`(visibility: String) {
        reject("command", "@io.cratis.arc.artifacts.Command $visibility class Input { public fun handle() {} }", "ARCKSP0101", "Command 'visibility.Input' must be public")
        reject("read-model", "@io.cratis.arc.artifacts.ReadModel $visibility class View { public companion object { public fun all(): View = View() } }", "ARCKSP0200", "Read model 'visibility.View' must be public")
        reject("companion", "@io.cratis.arc.artifacts.ReadModel public class View { $visibility companion object { public fun all(): View = View() } }", "ARCKSP0201", "Query 'visibility.View.all' must be declared in a public companion object")
    }

    @ParameterizedTest
    @ValueSource(strings = ["private", "internal", "protected"])
    fun `nonpublic command and query members remain rejected`(visibility: String) {
        reject("handle", "@io.cratis.arc.artifacts.Command public class Input { $visibility fun handle() {} }", "ARCKSP0102", "Handler 'visibility.Input.handle' must be public")
        reject("provide", "@io.cratis.arc.artifacts.Command public class Input { $visibility fun provide(): String = \"value\"; public fun handle(value: String) {} }", "ARCKSP0103", "Provide method 'visibility.Input.provide' must be public")
        reject("query", "@io.cratis.arc.artifacts.ReadModel public class View { public companion object { $visibility fun all(): View = View() } }", "ARCKSP0201", "Query 'visibility.View.all' must be public")
    }

    @Test
    fun `nested artifacts models and implicit computed command input retain shape diagnostics`() {
        reject("nested-command", "public class Outer { @io.cratis.arc.artifacts.Command class Input { fun handle() {} } }", "ARCKSP0101", "nested commands are not supported")
        reject("nested-read-model", "public class Outer { @io.cratis.arc.artifacts.ReadModel class View }", "ARCKSP0200", "nested read models are not supported")
        reject("nested-model", "public class Outer { class Child(val value: String) }; @io.cratis.arc.artifacts.Command class Input(val child: Outer.Child) { fun handle() {} }", "ARCKSP0300", "nested")
        reject("computed", "@io.cratis.arc.artifacts.Command class Input(val first: String) { val computed: String get() = first; fun handle() {} }", "ARCKSP0300", "Artifact/property 'visibility.Input.computed' is computed command input")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "private", "protected"])
    fun `Java package and nonpublic members remain rejected`(visibility: String) {
        rejectJava("handle", "@io.cratis.arc.artifacts.Command public class Input { $visibility void handle() {} }", "ARCKSP0102", "Handler 'visibility.Input.handle' must be public")
        rejectJava("provide", "@io.cratis.arc.artifacts.Command public class Input { $visibility String provide() { return \"value\"; } public void handle(String value) {} }", "ARCKSP0103", "Provide method 'visibility.Input.provide' must be public")
        rejectJava("query", "@io.cratis.arc.artifacts.ReadModel public class Input { $visibility static Input all() { return new Input(); } }", "ARCKSP0201", "Java query 'visibility.Input.all' must be public")
    }

    @Test
    fun `Java package artifact classes remain rejected`() {
        rejectJava("command", "@io.cratis.arc.artifacts.Command class Input { public void handle() {} }", "ARCKSP0101", "Command 'visibility.Input' must be public")
        rejectJava("read-model", "@io.cratis.arc.artifacts.ReadModel class Input { public static Input all() { return new Input(); } }", "ARCKSP0200", "Read model 'visibility.Input' must be public")
    }

    @Test
    fun `implicit command like public state now warns while nonpublic helpers do not`() {
        val result = compile(work.resolve("warning"), SourceFile.kotlin("Warning.kt", """
            package visibility
            class Unmarked(val value: String) { fun handle(): String = value }
            class Utility(private val value: String) { private fun handle(): String = value }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue("[ARCKSP0100]" in result.messages, result.messages)
        assertTrue("visibility.Unmarked" in result.messages, result.messages)
        assertFalse("visibility.Utility" in result.messages, result.messages)
    }

    @Test
    fun `newly visible provided values retain unused warning and duplicate implicit keys retain rejection`() {
        val result = compile(work.resolve("unused"), SourceFile.kotlin("Unused.kt", """
            package visibility
            @io.cratis.arc.artifacts.Command
            class Input(val value: String) {
                fun provide(): String = value
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue("[ARCKSP0107]" in result.messages, result.messages)
        reject("keys", "@io.cratis.arc.artifacts.Command class Input(@io.cratis.arc.artifacts.CommandKey val first: String) { @io.cratis.arc.artifacts.CommandKey var second: String = \"second\"; fun handle() {} }", "ARCKSP0106", "first, second. Exactly one is supported.")
        reject("external", "@io.cratis.arc.artifacts.Command class Input { fun handle() {} }; class External { fun handle(input: Input) {} }", "ARCKSP0102", "External handler 'visibility.External.handle' accepts an @Command type")
    }

    @Test
    fun `private constructor does not introduce an artifact visibility requirement`() {
        val result = compile(work.resolve("constructor"), SourceFile.kotlin("Constructor.kt", """
            package visibility
            @io.cratis.arc.artifacts.Command
            class Input private constructor(val value: String) {
                fun handle(): String = value
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.VisibilityArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule
        assertEquals(listOf("value"), module.commandHandlers.single().metadata.properties.map { it.name })
    }

    private fun reject(name: String, source: String, code: String, message: String) {
        val directory = work.resolve(name)
        assertRejected(directory, compile(directory, SourceFile.kotlin("Visibility.kt", "package visibility\n$source")), code, message)
    }

    private fun rejectJava(name: String, source: String, code: String, message: String) {
        val directory = work.resolve(name)
        assertRejected(directory, compile(directory, SourceFile.java("Input.java", "package visibility;\n$source")), code, message)
    }

    private fun assertRejected(directory: File, result: JvmCompilationResult, code: String, message: String) {
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[$code]" in result.messages, result.messages)
        assertTrue(message in result.messages, result.messages)
        assertFalse("[ARCKSP9999]" in result.messages, result.messages)
        assertFalse("Exception" in result.messages, result.messages)
        val generated = directory.resolve("ksp/sources").walkTopDown().filter(File::isFile).toList()
        assertFalse(generated.any { it.extension == "json" || it.name.contains("ArcArtifactModule") || it.name.contains("ArcArtifactMetadata") })
    }

    private fun compile(directory: File, vararg input: SourceFile): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        sources = input.toList()
        workingDir = directory
        inheritClassPath = true
        jvmTarget = "17"
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "Visibility")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
