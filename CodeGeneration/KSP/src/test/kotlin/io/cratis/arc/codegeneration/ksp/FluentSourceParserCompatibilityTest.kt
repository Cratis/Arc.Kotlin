// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FluentSourceParserCompatibilityTest {
    @Test
    fun `syntax APIs parse Kotlin literal rules and prove backed members without the environment guard`() {
        assertEquals("2.4.20", KotlinCompilerVersion.VERSION)
        val chains = FluentSourceParser.kotlin("""
            package fixture
            class Rules : FluentModelValidator<Model>(Model::class.java) {
                init { ruleFor("name").maxLength(12).withMessage("Name length") }
            }
        """.trimIndent(), "fixture", "Rules", "fixture.Model")
        assertEquals(listOf(listOf(FluentCall("ruleFor", listOf("name")), FluentCall("maxLength", listOf(12)),
            FluentCall("withMessage", listOf("Name length")))), chains)
        assertEquals(setOf("name", "identity"), FluentSourceParser.kotlinWireMembers("""
            package fixture
            class Model(val name: String) {
                val identity = "value"
                    get() = field
                val computed = "value"
                    get() = field.trim()
                val derived get() = name.trim()
            }
        """.trimIndent(), "fixture", "Model"))
    }

    @Test
    fun `syntax APIs parse Java literals records and identity accessors without the environment guard`() {
        val chains = FluentSourceParser.java("""
            package fixture;
            public final class Rules extends FluentModelValidator<Model> {
                public Rules() { super(Model.class); ruleFor("score").greaterThan(-2L).withMessage("Score bound"); }
            }
        """.trimIndent(), "fixture", "Rules", "fixture.Model")
        assertEquals(listOf(listOf(FluentCall("ruleFor", listOf("score")), FluentCall("greaterThan", listOf(-2L)),
            FluentCall("withMessage", listOf("Score bound")))), chains)
        val record = "package fixture; public record Model(String name, long score) {}"
        assertEquals(mapOf("name" to "java.lang.String", "score" to "long"),
            FluentSourceParser.javaRecordTypes(record, "fixture", "Model") { type, _ -> "java.lang.$type" })
        assertEquals(setOf("name", "score"), FluentSourceParser.javaWireMembers(record, "fixture", "Model"))
        assertEquals(setOf("score"), FluentSourceParser.javaWireMembers("""
            package fixture;
            public record Model(String name, long score) {
                public String name() { return name.trim(); }
                public long score() { return this.score; }
            }
        """.trimIndent(), "fixture", "Model"))
    }

    @Test
    fun `only the exact embedded compiler and full supported LTS runtimes are accepted`() {
        listOf(17, 21).forEach { FluentSourceParser.requireEnvironment("2.4.20", it, true) }
        val unsupported = listOf("2.4.19", "2.4.21", "unknown").map { Triple(it, 17, true) } +
            listOf(16, 18, 20, 22, 26).map { Triple("2.4.20", it, true) } + Triple("2.4.20", 21, false)
        unsupported.forEach { (compiler, runtime, javac) ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                FluentSourceParser.requireEnvironment(compiler, runtime, javac)
            }
            val message = requireNotNull(failure.message)
            listOf("kotlin-compiler-embeddable 2.4.20", "JDK17 or JDK21", "jdk.compiler",
                "compiler=$compiler", "processor runtime=$runtime", "javac=$javac",
                "retain arc-ksp's published parser dependency", "supported full JDK").forEach {
                assertTrue(it in message, message)
            }
        }
    }
}
