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
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Positive-path KSP compile tests for the three Hibernate/Jakarta annotation mappings added in
 * issue 172: `@Range`, `@Length`, and `@Digits`.  Each test asserts the exact `RULE_ORDER`
 * position and arguments that `ValidationMetadataExtractor` emits.
 */
@OptIn(ExperimentalCompilerApi::class)
internal class ArcAnnotationValidationCompilationTest {
    @TempDir lateinit var work: File

    // ── @Range ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `@Range with explicit min and max emits greaterThanOrEqual then lessThanOrEqual in RULE_ORDER`() {
        val result = compile(SourceFile.kotlin("RangeBoth.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import org.hibernate.validator.constraints.Range
            @Command
            class RangeBothCommand(@field:Range(min = 1, max = 100) val value: Long) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("greaterThanOrEqual", "lessThanOrEqual"), rules.map { it.ruleName })
        assertEquals(listOf(1), rules[0].arguments)
        assertEquals(listOf(100), rules[1].arguments)
    }

    @Test
    fun `@Range with max only still emits greaterThanOrEqual(0) because Hibernate enforces its lower bound`() {
        val result = compile(SourceFile.kotlin("RangeMaxOnly.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import org.hibernate.validator.constraints.Range
            @Command
            class RangeMaxOnlyCommand(@field:Range(max = 50) val value: Int) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("greaterThanOrEqual", "lessThanOrEqual"), rules.map { it.ruleName })
        assertEquals(listOf(0), rules[0].arguments)
        assertEquals(listOf(50), rules[1].arguments)
    }

    @Test
    fun `@Range with min only omits lessThanOrEqual because max defaults to Long MAX_VALUE`() {
        val result = compile(SourceFile.kotlin("RangeMinOnly.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import org.hibernate.validator.constraints.Range
            @Command
            class RangeMinOnlyCommand(@field:Range(min = 5) val value: Int) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("greaterThanOrEqual"), rules.map { it.ruleName })
        assertEquals(listOf(5), rules[0].arguments)
    }

    @Test
    fun `Java @Range with both bounds emits same rules as Kotlin`() {
        val result = compile(SourceFile.java("JavaRangeBothCommand.java", """
            package annotations;
            import io.cratis.arc.artifacts.Command;
            import org.hibernate.validator.constraints.Range;
            @Command
            public final class JavaRangeBothCommand {
                @Range(min = 2, max = 200) public long value;
                public void handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("greaterThanOrEqual", "lessThanOrEqual"), rules.map { it.ruleName })
        assertEquals(listOf(2), rules[0].arguments)
        assertEquals(listOf(200), rules[1].arguments)
    }

    @Test
    fun `Java record components carry pre-existing Jakarta annotations`() {
        val result = compile(SourceFile.java("JavaSizeRecordCommand.java", """
            package annotations;
            import io.cratis.arc.artifacts.Command;
            import jakarta.validation.constraints.Size;
            @Command
            public record JavaSizeRecordCommand(@Size(min = 2, max = 20) String value) {
                public void handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("length"), rules.map { it.ruleName })
    }

    @Test
    fun `Java record components carry validation annotations just like fields`() {
        val result = compile(SourceFile.java("JavaRangeRecordCommand.java", """
            package annotations;
            import io.cratis.arc.artifacts.Command;
            import org.hibernate.validator.constraints.Range;
            @Command
            public record JavaRangeRecordCommand(@Range(min = 2, max = 200) long value) {
                public void handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("greaterThanOrEqual", "lessThanOrEqual"), rules.map { it.ruleName })
        assertEquals(listOf(2), rules[0].arguments)
        assertEquals(listOf(200), rules[1].arguments)
    }

    @Test
    fun `Java @Range with max only still emits greaterThanOrEqual(0)`() {
        val result = compile(SourceFile.java("JavaRangeMaxOnlyCommand.java", """
            package annotations;
            import io.cratis.arc.artifacts.Command;
            import org.hibernate.validator.constraints.Range;
            @Command
            public final class JavaRangeMaxOnlyCommand {
                @Range(max = 99) public int value;
                public void handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("greaterThanOrEqual", "lessThanOrEqual"), rules.map { it.ruleName })
        assertEquals(listOf(0), rules[0].arguments)
        assertEquals(listOf(99), rules[1].arguments)
    }

    // ── @Length ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `@Length with min only emits minLength in RULE_ORDER`() {
        val result = compile(SourceFile.kotlin("LengthMinOnly.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import org.hibernate.validator.constraints.Length
            @Command
            class LengthMinOnlyCommand(@field:Length(min = 2) val value: String) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("minLength"), rules.map { it.ruleName })
        assertEquals(listOf(2), rules[0].arguments)
    }

    @Test
    fun `@Length with max only emits maxLength in RULE_ORDER`() {
        val result = compile(SourceFile.kotlin("LengthMaxOnly.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import org.hibernate.validator.constraints.Length
            @Command
            class LengthMaxOnlyCommand(@field:Length(max = 20) val value: String) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("maxLength"), rules.map { it.ruleName })
        assertEquals(listOf(20), rules[0].arguments)
    }

    @Test
    fun `@Length with both min and max emits length in RULE_ORDER`() {
        val result = compile(SourceFile.kotlin("LengthBoth.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import org.hibernate.validator.constraints.Length
            @Command
            class LengthBothCommand(@field:Length(min = 3, max = 15) val value: String) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("length"), rules.map { it.ruleName })
        assertEquals(listOf(3, 15), rules[0].arguments)
    }

    @Test
    fun `Java @Length follows the same three-branch minLength maxLength length mapping`() {
        val result = compile(SourceFile.java("JavaLengthCommand.java", """
            package annotations;
            import io.cratis.arc.artifacts.Command;
            import org.hibernate.validator.constraints.Length;
            @Command
            public final class JavaLengthCommand {
                @Length(min = 1) public String minOnly;
                @Length(max = 30) public String maxOnly;
                @Length(min = 2, max = 25) public String both;
                public void handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val properties = module(result).commandHandlers.single().metadata.properties.associateBy { it.name }
        assertEquals(listOf("minLength"), properties.getValue("minOnly").validationRules.map { it.ruleName })
        assertEquals(listOf(1), properties.getValue("minOnly").validationRules[0].arguments)
        assertEquals(listOf("maxLength"), properties.getValue("maxOnly").validationRules.map { it.ruleName })
        assertEquals(listOf(30), properties.getValue("maxOnly").validationRules[0].arguments)
        assertEquals(listOf("length"), properties.getValue("both").validationRules.map { it.ruleName })
        assertEquals(listOf(2, 25), properties.getValue("both").validationRules[0].arguments)
    }

    // ── @Digits ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `@Digits with fraction 0 emits matches with integer-only regex`() {
        val result = compile(SourceFile.kotlin("DigitsFractionZero.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import jakarta.validation.constraints.Digits
            @Command
            class DigitsFractionZeroCommand(@field:Digits(integer = 5, fraction = 0) val value: String) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("matches"), rules.map { it.ruleName })
        assertEquals(listOf("""^[+-]?\d{1,5}$"""), rules[0].arguments)
    }

    @Test
    fun `@Digits with fraction greater than 0 emits matches with optional decimal group`() {
        val result = compile(SourceFile.kotlin("DigitsFractionPositive.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import jakarta.validation.constraints.Digits
            @Command
            class DigitsFractionPositiveCommand(@field:Digits(integer = 3, fraction = 2) val value: String) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("matches"), rules.map { it.ruleName })
        assertEquals(listOf("""^[+-]?\d{1,3}(\.\d{1,2})?$"""), rules[0].arguments)
    }

    @Test
    fun `@Digits on a numeric member also emits matches`() {
        val result = compile(SourceFile.kotlin("DigitsNumeric.kt", """
            package annotations
            import io.cratis.arc.artifacts.Command
            import jakarta.validation.constraints.Digits
            @Command
            class DigitsNumericCommand(@field:Digits(integer = 4, fraction = 0) val value: Int) {
                fun handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val rules = module(result).commandHandlers.single().metadata.properties.single().validationRules
        assertEquals(listOf("matches"), rules.map { it.ruleName })
        assertEquals(listOf("""^[+-]?\d{1,4}$"""), rules[0].arguments)
    }

    @Test
    fun `Java @Digits fraction 0 and fraction positive emit correct matches patterns`() {
        val result = compile(SourceFile.java("JavaDigitsCommand.java", """
            package annotations;
            import io.cratis.arc.artifacts.Command;
            import jakarta.validation.constraints.Digits;
            @Command
            public final class JavaDigitsCommand {
                @Digits(integer = 6, fraction = 0) public String integerOnly;
                @Digits(integer = 2, fraction = 3) public String withFraction;
                public void handle() {}
            }
        """.trimIndent()))
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val properties = module(result).commandHandlers.single().metadata.properties.associateBy { it.name }
        assertEquals(listOf("""^[+-]?\d{1,6}$"""), properties.getValue("integerOnly").validationRules[0].arguments)
        assertEquals(listOf("""^[+-]?\d{1,2}(\.\d{1,3})?$"""), properties.getValue("withFraction").validationRules[0].arguments)
    }

    private fun module(result: JvmCompilationResult): ArcArtifactModule = result.classLoader
        .loadClass("io.cratis.arc.generated.AnnotationsArcArtifactModule").getDeclaredConstructor().newInstance() as ArcArtifactModule

    private fun compile(vararg sources: SourceFile): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources.toList()
        workingDir = work
        inheritClassPath = true
        jvmTarget = "17"
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "Annotations")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
