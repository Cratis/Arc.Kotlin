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
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCompilerApi::class)
internal class FundamentalsConceptCompilationTest {
    @TempDir lateinit var work: File

    @Test
    fun `legacy and fundamentals concepts retain identical Guid shapes and collect constraints once`() {
        val result = compileConcepts()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.AdoptionArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        val properties = module.types.single { it.fullyQualifiedName == "fixture.Owner" }.properties
        assertEquals(2, properties.size)
        assertEquals(setOf("fixture.LegacyId", "fixture.ModernId"), module.concepts.map { it.fullyQualifiedName }.toSet())
        properties.forEach { property ->
            val concept = module.concepts.single { it.fullyQualifiedName == property.shape.typeName }
            // The shared UUID underlying type is rendered as Guid by the TypeScript generator.
            assertEquals("java.util.UUID", concept.underlyingTypeName)
            assertEquals(listOf("notNull"), property.validationRules.map { it.ruleName })
        }
        assertEquals(properties[0].validationRules, properties[1].validationRules)
    }

    private fun compileConcepts() = KotlinCompilation().apply {
        workingDir = work
        useKsp2()
        sources = listOf(SourceFile.kotlin("Adoption.kt", """
            @file:Suppress("DEPRECATION")
            package fixture
            import java.util.UUID
            import jakarta.validation.constraints.NotNull
            class LegacyId(@field:NotNull private val id: UUID) : io.cratis.arc.concepts.ConceptAs<UUID> {
                override fun value(): UUID = id
            }
            class ModernId(@field:NotNull private val id: UUID) : io.cratis.fundamentals.concepts.ConceptAs<UUID> {
                override fun value(): UUID = id
            }
            @io.cratis.arc.artifacts.ExportedType
            class Owner(val legacy: LegacyId, val modern: ModernId)
        """.trimIndent()))
        inheritClassPath = true
        allWarningsAsErrors = true
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "Adoption")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
