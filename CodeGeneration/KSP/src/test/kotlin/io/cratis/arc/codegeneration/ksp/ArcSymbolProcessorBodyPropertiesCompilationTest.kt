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
import io.cratis.arc.json.ArcObjectMapper
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorBodyPropertiesCompilationTest {
    @TempDir lateinit var work: File

    @Test
    fun `constructor order appends backed body state keys constraints and reachable models without ignored members`() {
        val result = compile("""
            package bodies
            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.CommandKey
            import com.fasterxml.jackson.annotation.JsonIgnore
            import jakarta.validation.Valid
            import jakarta.validation.constraints.NotBlank
            import jakarta.validation.constraints.Size
            public class Child(public val first: String) { public var last: String = "last" }
            @Command
            public class Mixed(public val zulu: String, public val alpha: String) {
                @get:Size(min = 2) public var title: String = "title"
                    private set
                /** Stable body identifier. */
                @CommandKey @field:NotBlank public var id: String = "key"
                @field:Valid public var child: Child = Child("first")
                public val backed: String = "backed"
                @get:JsonIgnore public val computed: String get() = zulu + alpha
                @field:JsonIgnore public var secret: String = "secret"
                @get:JsonIgnore(false) public var visible: String = "visible"
                private val privateValue: String = "private"
                protected val protectedValue: String = "protected"
                public fun handle(): String = id
            }
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = module(result)
        val handler = module.commandHandlers.single()
        val names = listOf("zulu", "alpha", "backed", "child", "id", "title", "visible")
        assertEquals(names, handler.metadata.properties.map { it.name })
        assertEquals(handler.metadata.properties, module.types.single { it.name == "Mixed" }.properties)
        assertEquals(listOf("first", "last"), module.types.single { it.name == "Child" }.properties.map { it.name })
        val id = handler.metadata.properties.single { it.name == "id" }
        assertTrue(id.isCommandKey)
        assertEquals("Stable body identifier.", id.summary)
        assertTrue(id.validationRules.isNotEmpty())
        assertTrue(handler.metadata.properties.single { it.name == "title" }.validationRules.isNotEmpty())
        assertTrue(handler.metadata.properties.single { it.name == "child" }.validateRecursively)
        val instance = ArcObjectMapper.create().readValue(
            """{"zulu":"z","alpha":"a","id":"supplied-key","title":"supplied-title","backed":"supplied-backed"}""",
            handler.commandType
        )
        assertEquals("supplied-key", handler.resolveCommandKey(instance))
        val tree = ArcObjectMapper.create().readTree(ArcObjectMapper.create().writeValueAsString(instance))
        assertEquals(names.toSet(), tree.propertyNames().asSequence().toSet())
        assertEquals("supplied-title", tree["title"].stringValue())
        assertEquals("supplied-backed", tree["backed"].stringValue())
        val sources = work.resolve("ksp/sources/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.any { it.readText().contains("typedCommand.id") || it.readText().contains("command.id") }, sources.toString())
    }

    @Test
    fun `constructor plus body duplicate command keys fail instead of silently selecting the constructor`() {
        val result = compile("""
            package bodies
            @io.cratis.arc.artifacts.Command
            public class Duplicate(@io.cratis.arc.artifacts.CommandKey public val first: String) {
                @io.cratis.arc.artifacts.CommandKey public var second: String = "second"
                public fun handle() { }
            }
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0106]" in result.messages, result.messages)
        assertTrue("first, second. Exactly one is supported." in result.messages, result.messages)
        assertFalse(manifest().exists())
    }

    @Test
    fun `output only computed state and declared inheritance remain separate and complete`() {
        val result = compile("""
            package bodies
            public open class Base(public val first: String) {
                public var baseBody: String = "base"
                public open val label: String get() = first
            }
            @io.cratis.arc.artifacts.ReadModel
            public class Output(public val own: String) : Base(own) {
                public var body: String = "body"
                public override val label: String get() = "derived-" + own
                public companion object { @JvmStatic public fun find(): Output = Output("output") }
            }
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val types = module(result).types.associateBy { it.name }
        assertEquals(listOf("first", "baseBody", "label"), types.getValue("Base").properties.map { it.name })
        assertEquals(listOf("own", "body", "label"), types.getValue("Output").properties.map { it.name })
        assertEquals("bodies.Base", types.getValue("Output").baseTypeName)
        val output = result.classLoader.loadClass("bodies.Output").getConstructor(String::class.java).newInstance("output")
        val tree = ArcObjectMapper.create().readTree(ArcObjectMapper.create().writeValueAsString(output))
        assertEquals(setOf("first", "baseBody", "label", "own", "body"), tree.propertyNames().asSequence().toSet())
        assertEquals("derived-output", tree["label"].stringValue())
    }

    @Test
    fun `computed command input gets an actionable unsupported shape instead of required writable metadata`() {
        val result = compile("""
            package bodies
            @io.cratis.arc.artifacts.Command
            public class Computed(public val first: String) {
                public val calculated: String get() = first
                public fun handle() { }
            }
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0300]" in result.messages, result.messages)
        assertTrue("bodies.Computed.calculated" in result.messages, result.messages)
        assertTrue("computed command input" in result.messages, result.messages)
        assertFalse(manifest().exists())
    }

    @Test
    fun `Java record commands cannot bypass computed input validation through records interfaces or public fields`() {
        val result = compile(listOf(
            SourceFile.kotlin("BodyModels.kt", """
                package bodies
                public class Direct(public val first: String) { public val direct: String get() = first }
                public class Nested(public val first: String) { public val nested: String get() = first }
                public class Viewed(public val first: String) { public val viewed: String get() = first }
                public class Fielded(public val first: String) { public val fielded: String get() = first }
            """.trimIndent()),
            SourceFile.java("JavaInput.java", """
                package bodies;
                @io.cratis.arc.artifacts.Command
                public record JavaInput(Direct direct, Bridge bridge, View view, Fields fields) {
                    public void handle() { }
                }
            """.trimIndent()),
            SourceFile.java("Bridge.java", """
                package bodies;
                public record Bridge(java.util.List<Nested> values) { }
            """.trimIndent()),
            SourceFile.java("View.java", """
                package bodies;
                public interface View { Viewed getValue(); }
            """.trimIndent()),
            SourceFile.java("Fields.java", """
                package bodies;
                public class Fields { public Fielded value; }
            """.trimIndent())
        ))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for ((owner, member) in listOf("Direct" to "direct", "Nested" to "nested", "Viewed" to "viewed", "Fielded" to "fielded")) {
            assertTrue("[ARCKSP0300] Artifact/property 'bodies.$owner.$member' is computed command input; " +
                "use a backed property, @JsonIgnore, or a separate output model." in result.messages, result.messages)
        }
        assertFalse("[ARCKSP9999]" in result.messages, result.messages)
        assertFalse(manifest().exists())
    }

    @Test
    fun `Java record input accepts backed Kotlin body models while read only state stays output only`() {
        val result = compile(listOf(
            SourceFile.kotlin("BodyModels.kt", """
                package bodies
                import com.fasterxml.jackson.annotation.JsonProperty
                public class Writable(public val first: String) {
                    public var body: String = "body"
                    public val backed: String = "backed"
                }
                public class Output(public val first: String) {
                    @get:JsonProperty(access = JsonProperty.Access.READ_ONLY)
                    public var body: String = "output"
                    public val computed: String get() = first + body
                }
            """.trimIndent()),
            SourceFile.java("JavaInput.java", """
                package bodies;
                @io.cratis.arc.artifacts.Command
                public record JavaInput(Writable value) {
                    public Output handle() { return new Output(value.getBody()); }
                }
            """.trimIndent())
        ))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val types = module(result).types.associateBy { it.name }
        assertEquals(listOf("first", "backed", "body"), types.getValue("Writable").properties.map { it.name })
        assertEquals(listOf("first", "body", "computed"), types.getValue("Output").properties.map { it.name })
        val commandType = result.classLoader.loadClass("bodies.JavaInput")
        val mapper = ArcObjectMapper.create()
        val command = mapper.readValue("""{"value":{"first":"first","body":"supplied","backed":"final-input"}}""", commandType)
        val output = commandType.getMethod("handle").invoke(command)
        val tree = mapper.readTree(mapper.writeValueAsString(output))
        assertEquals("suppliedoutput", tree["computed"].stringValue())
        val input = mapper.readTree(mapper.writeValueAsString(command))["value"]
        assertEquals("supplied", input["body"].stringValue())
        assertEquals("final-input", input["backed"].stringValue())
    }

    @Test
    fun `body Jackson rename write only split ignore and read only input fail precisely`() {
        val result = compile("""
            package bodies
            import com.fasterxml.jackson.annotation.JsonIgnore
            import com.fasterxml.jackson.annotation.JsonProperty
            import io.cratis.arc.artifacts.Command
            @Command public class Renamed(public val first: String) {
                @get:JsonProperty("wire_value") public var value: String = "value"
                public fun handle() { }
            }
            @Command public class WriteOnly(public val first: String) {
                @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) public var value: String = "value"
                public fun handle() { }
            }
            @Command public class Split(public val first: String) {
                @get:JsonIgnore @set:JsonProperty public var value: String = "value"
                public fun handle() { }
            }
            @Command public class ReadOnly(public val first: String) {
                @get:JsonProperty(access = JsonProperty.Access.READ_ONLY) public var value: String = "value"
                public fun handle() { }
            }
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (owner in listOf("Renamed", "WriteOnly", "Split")) {
            assertTrue("[ARCKSP0300] Artifact/property 'bodies.$owner.value' has body-property Jackson names or access that metadata cannot represent; " +
                "use the default Arc wire name and symmetric access, or a separate wire model." in result.messages, result.messages)
        }
        assertTrue("[ARCKSP0300] Artifact/property 'bodies.ReadOnly.value' is read-only command input; " +
            "use symmetric Jackson access, @JsonIgnore, or a separate output model." in result.messages, result.messages)
        assertFalse(manifest().exists())
    }

    @Test
    fun `Java record command resolves imported temporal components before source fallback`() {
        val result = compile(listOf(SourceFile.java("ImportedDate.java", """
            package bodies;
            import java.time.LocalDate;
            @io.cratis.arc.artifacts.Command
            public record ImportedDate(LocalDate date) { public void handle() { } }
        """.trimIndent())))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val property = module(result).commandHandlers.single().metadata.properties.single()
        assertEquals("date", property.name)
        assertEquals("java.time.LocalDate", property.typeName)
    }

    @Test
    fun `Java record command resolves imported backed Kotlin body models`() {
        val result = compile(importedBodySources(computed = false))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = module(result)
        assertEquals("external.Child", module.commandHandlers.single().metadata.properties.single().typeName)
        assertEquals(listOf("first", "body"), module.types.single { it.name == "Child" }.properties.map { it.name })
        val mapper = ArcObjectMapper.create()
        val command = mapper.readValue("""{"value":{"first":"first","body":"supplied"}}""",
            module.commandHandlers.single().commandType)
        assertEquals("supplied", mapper.readTree(mapper.writeValueAsString(command))["value"]["body"].stringValue())
    }

    @Test
    fun `imported computed Kotlin child still rejects Java root at the child member`() {
        val result = compile(importedBodySources(computed = true))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0300] Artifact/property 'external.Child.body' is computed command input; " +
            "use a backed property, @JsonIgnore, or a separate output model." in result.messages, result.messages)
        assertFalse("unresolvable Java type" in result.messages, result.messages)
        assertFalse("[ARCKSP9999]" in result.messages, result.messages)
        assertFalse(manifest().exists())
    }

    @Test
    fun `explicit read write body access is symmetric in command input and output only models`() {
        val result = compile("""
            package bodies
            import com.fasterxml.jackson.annotation.JsonProperty
            @io.cratis.arc.artifacts.Command
            public class Symmetric(public val first: String) {
                @get:JsonProperty(access = JsonProperty.Access.READ_WRITE)
                public var body: String = "input"
                public fun handle(): SymmetricOutput = SymmetricOutput(first).also { it.body = body }
            }
            public class SymmetricOutput(public val first: String) {
                @field:JsonProperty(access = JsonProperty.Access.READ_WRITE)
                public var body: String = "output"
                public val computed: String get() = first + body
            }
        """.trimIndent())
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = module(result)
        assertEquals(listOf("first", "body"), module.commandHandlers.single().metadata.properties.map { it.name })
        assertEquals(listOf("first", "body", "computed"),
            module.types.single { it.name == "SymmetricOutput" }.properties.map { it.name })
        val mapper = ArcObjectMapper.create()
        val commandType = module.commandHandlers.single().commandType
        val command = mapper.readValue("""{"first":"first","body":"supplied"}""", commandType)
        val output = commandType.getMethod("handle").invoke(command)
        val tree = mapper.readTree(mapper.writeValueAsString(output))
        assertEquals("supplied", tree["body"].stringValue())
        assertEquals("firstsupplied", tree["computed"].stringValue())
    }

    private fun importedBodySources(computed: Boolean): List<SourceFile> = listOf(
        SourceFile.kotlin("ImportedChild.kt", """
            package external
            public class Child(public val first: String) {
                ${if (computed) "public val body: String get() = first" else "public var body: String = first"}
            }
        """.trimIndent()),
        SourceFile.java("ImportedBody.java", """
            package bodies;
            import external.Child;
            @io.cratis.arc.artifacts.Command
            public record ImportedBody(Child value) { public void handle() { } }
        """.trimIndent())
    )

    private fun module(result: JvmCompilationResult): ArcArtifactModule = result.classLoader
        .loadClass("io.cratis.arc.generated.BodiesArcArtifactModule").getDeclaredConstructor().newInstance() as ArcArtifactModule

    private fun manifest(): File = work.resolve("ksp/sources/resources/META-INF/cratis/arc/Bodies.json")

    private fun compile(source: String): JvmCompilationResult = compile(listOf(SourceFile.kotlin("Bodies.kt", source)))

    private fun compile(input: List<SourceFile>): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        sources = input
        workingDir = work
        inheritClassPath = true
        jvmTarget = "17"
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "Bodies")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
