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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorCommandOptionalCompilationTest {
    @Test
    fun `provided Java value consumed through Optional compiles without an unused value warning`() {
        val result = compile(
            SourceFile.java(
                "OptionalCompositionCommand.java",
                """
                package optionalcontracts.positive;

                import io.cratis.arc.artifacts.Command;
                import java.util.Optional;

                @Command
                public record OptionalCompositionCommand(String value) {
                    public record OptionalValue(String value) {}

                    public OptionalValue provide() { return new OptionalValue(value); }
                    public String handle(Optional<OptionalValue> dependency) {
                        return dependency.map(OptionalValue::value).orElse("missing");
                    }
                }
                """.trimIndent()
            ),
            packageAnchor("optionalcontracts.positive", "PositiveOptionalPackageAnchor")
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue("[ARCKSP0107]" !in result.messages, result.messages)
    }

    @Test
    fun `provided Java value not consumed by the Optional argument retains the unused value warning`() {
        val result = compile(
            SourceFile.java(
                "UnconsumedOptionalCompositionCommand.java",
                """
                package optionalcontracts.unconsumed;

                import io.cratis.arc.artifacts.Command;
                import java.util.Optional;

                @Command
                public record UnconsumedOptionalCompositionCommand(String value) {
                    public record ProvidedValue(String value) {}
                    public record OtherValue(String value) {}

                    public ProvidedValue provide() { return new ProvidedValue(value); }
                    public String handle(Optional<OtherValue> dependency) { return value; }
                }
                """.trimIndent()
            ),
            packageAnchor("optionalcontracts.unconsumed", "UnconsumedOptionalPackageAnchor")
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue("[ARCKSP0107]" in result.messages, result.messages)
        assertTrue(
            "optionalcontracts.unconsumed.UnconsumedOptionalCompositionCommand.ProvidedValue" in result.messages,
            result.messages
        )
    }

    @Test
    fun `raw wildcard nested and Kotlin Optional command parameters fail deterministically`() {
        val result = compile(
            SourceFile.java(
                "OptionalValue.java",
                """
                package optionalcontracts.negative;

                public final class OptionalValue {}
                """.trimIndent()
            ),
            invalidJavaCommand("RawOptionalCommand", "Optional"),
            invalidJavaCommand("WildcardOptionalCommand", "Optional<?>"),
            invalidJavaCommand("NestedOptionalCommand", "Optional<Optional<OptionalValue>>"),
            SourceFile.kotlin(
                "InvalidKotlinOptionalCommands.kt",
                """
                package optionalcontracts.negative

                import io.cratis.arc.artifacts.Command
                import java.util.Optional

                @Command
                public data class KotlinOptionalCommand(public val value: String) {
                    public fun handle(dependency: Optional<OptionalValue>): String = value
                }

                @Command
                public data class NullableKotlinOptionalCommand(public val value: String) {
                    public fun handle(dependency: Optional<OptionalValue>?): String = value
                }
                """.trimIndent()
            )
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertEquals(
            3,
            Regex("must have one concrete, invariant, non-null, non-parameterized class or interface type")
                .findAll(result.messages)
                .count(),
            result.messages
        )
        assertEquals(
            2,
            Regex("must not use java.util.Optional from Kotlin")
                .findAll(result.messages)
                .count(),
            result.messages
        )
        assertTrue("[ARCKSP0104]" in result.messages, result.messages)
    }

    private fun packageAnchor(packageName: String, name: String): SourceFile = SourceFile.kotlin(
        "$name.kt",
        """
        package $packageName

        internal object $name
        """.trimIndent()
    )

    private fun invalidJavaCommand(name: String, parameterType: String): SourceFile = SourceFile.java(
        "$name.java",
        """
        package optionalcontracts.negative;

        import io.cratis.arc.artifacts.Command;
        import java.util.Optional;

        @Command
        public record $name(String value) {
            public String handle($parameterType dependency) { return value; }
        }
        """.trimIndent()
    )

    private fun compile(vararg sources: SourceFile): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources.toList()
        inheritClassPath = true
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "OptionalContracts")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
