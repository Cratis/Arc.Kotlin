// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class MetadataCollectorPropertyDiagnosticCompilationTest {
    @Test
    fun `property diagnostics use real Kotlin and Java member nodes and only fall back for missing record members`() {
        val provider = DiagnosticSiteProvider()
        val result = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(
                SourceFile.kotlin("DiagnosticSites.kt", """
                    package polymorphic.sites
                    import io.cratis.arc.polymorphism.DerivedType
                    public open class Base
                    @DerivedType("site-leaf")
                    public class Leaf : Base()
                    public data class KotlinHolder(public val value: Base)
                """.trimIndent()),
                SourceFile.java("JavaHolder.java", """
                    package polymorphic.sites;
                    public record JavaHolder(Base value) { }
                """.trimIndent()),
                SourceFile.java("JavaView.java", """
                    package polymorphic.sites;
                    public interface JavaView { Base getValue(); }
                """.trimIndent()),
                SourceFile.java("FallbackHolder.java", """
                    package polymorphic.sites;
                    public record FallbackHolder(Base value) { }
                """.trimIndent())
            )
            inheritClassPath = true
            symbolProcessorProviders = mutableListOf(provider)
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertEquals(4, provider.sites.size, result.messages)
        assertTrue("KotlinHolder.value:property" in provider.sites, provider.sites.toString())
        assertTrue(provider.sites.any { it == "JavaHolder.value:property" || it == "JavaHolder.value:accessor" })
        assertTrue("JavaView.getValue:accessor" in provider.sites, provider.sites.toString())
        assertTrue("FallbackHolder:class" in provider.sites, provider.sites.toString())
        for (owner in listOf("KotlinHolder", "JavaHolder", "JavaView", "FallbackHolder")) {
            val expected = "[ARCKSP0305] Artifact/property 'polymorphic.sites.$owner.value' declares concrete " +
                "polymorphic base 'polymorphic.sites.Base' with visible @DerivedType descendants; " +
                "declare an interface or abstract base instead."
            assertTrue(expected in result.messages, result.messages)
        }
    }

    @Test
    fun `nullable sequence diagnostics retain member sites including Java type use and record fallback`() {
        val sites = mutableListOf<String>()
        val fallbackKinds = mutableListOf<io.cratis.arc.metadata.SequenceKind?>()
        val provider = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
                val logger = object : KSPLogger by environment.logger {
                    override fun error(message: String, symbol: KSNode?) {
                        if (message.startsWith("[ARCKSP0300]")) {
                            val member = symbol as? KSDeclaration
                            sites += "${member?.parentDeclaration?.simpleName?.asString()}.${member?.simpleName?.asString()}"
                        }
                        environment.logger.error(message, symbol)
                    }
                }
                val collector = MetadataCollector(ArcDiagnosticReporter(logger))
                return object : SymbolProcessor {
                    override fun process(resolver: Resolver): List<KSAnnotated> {
                        collector.useResolver(resolver)
                        for (name in listOf("KotlinSequence", "JavaSequence", "JavaSequenceView", "JavaSequenceField", "FallbackSequence", "FallbackValidSequence")) {
                            val declaration = requireNotNull(resolver.getClassDeclarationByName(
                                resolver.getKSNameFromString("sequences.sites.$name")
                            ))
                            val owner = if (name.startsWith("Fallback")) RecordWithoutMembers(declaration) else declaration
                            val properties = collector.describeProperties(owner, "sequences.sites.$name")
                            if (name == "FallbackValidSequence") {
                                val valid = requireNotNull(properties)
                                fallbackKinds += valid.map { it.shape.sequenceKind }
                                assertTrue(valid.all { !requireNotNull(it.shape.elementShape).nullable })
                            }
                        }
                        return emptyList()
                    }
                }
            }
        }
        val result = KotlinCompilation().apply {
            useKsp2()
            sources = listOf(
                SourceFile.kotlin("KotlinSequence.kt", """
                    package sequences.sites
                    public data class KotlinSequence(public val values: List<String?>?)
                """.trimIndent()),
                SourceFile.java("Nullable.java", """
                    package sequences.sites;
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                    public @interface Nullable { }
                """.trimIndent()),
                SourceFile.java("JavaSequence.java", """
                    package sequences.sites;
                    public record JavaSequence(java.util.List<@Nullable String> values) { }
                """.trimIndent()),
                SourceFile.java("JavaSequenceView.java", """
                    package sequences.sites;
                    public interface JavaSequenceView { java.util.List<@Nullable String> getValues(); }
                """.trimIndent()),
                SourceFile.java("JavaSequenceField.java", """
                    package sequences.sites;
                    public class JavaSequenceField { public java.util.List<@Nullable String> values; }
                """.trimIndent()),
                SourceFile.java("FallbackSequence.java", """
                    package sequences.sites;
                    public record FallbackSequence(java.util.List<@Nullable String> values) { }
                """.trimIndent()),
                SourceFile.java("FallbackValidSequence.java", """
                    package sequences.sites;
                    public record FallbackValidSequence(java.util.List<String> list, java.util.Collection<String> collection, String[] array) { }
                """.trimIndent())
            )
            inheritClassPath = true
            jvmTarget = "17"
            symbolProcessorProviders = mutableListOf(provider)
            kspWithCompilation = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertEquals(setOf("KotlinSequence.values", "JavaSequence.values", "JavaSequenceView.getValues",
            "JavaSequenceField.values", "null.FallbackSequence"), sites.toSet(), result.messages)
        assertEquals(5, sites.size, result.messages)
        assertEquals(listOf(io.cratis.arc.metadata.SequenceKind.LIST, io.cratis.arc.metadata.SequenceKind.COLLECTION,
            io.cratis.arc.metadata.SequenceKind.ARRAY), fallbackKinds)
    }

    private class DiagnosticSiteProvider : SymbolProcessorProvider {
        val sites = mutableListOf<String>()

        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
            val logger = object : KSPLogger by environment.logger {
                override fun error(message: String, symbol: KSNode?) {
                    if (message.startsWith("[ARCKSP0305]")) {
                        sites += when (symbol) {
                            is KSPropertyDeclaration ->
                                "${symbol.parentDeclaration?.simpleName?.asString()}.${symbol.simpleName.asString()}:property"
                            is KSFunctionDeclaration ->
                                "${symbol.parentDeclaration?.simpleName?.asString()}.${symbol.simpleName.asString()}:accessor"
                            is KSClassDeclaration -> "${symbol.simpleName.asString()}:class"
                            else -> "unexpected:$symbol"
                        }
                    }
                    environment.logger.error(message, symbol)
                }
            }
            val collector = MetadataCollector(ArcDiagnosticReporter(logger))
            return object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    collector.useResolver(resolver)
                    for (name in listOf("KotlinHolder", "JavaHolder", "JavaView", "FallbackHolder")) {
                        val declaration = requireNotNull(resolver.getClassDeclarationByName(
                            resolver.getKSNameFromString("polymorphic.sites.$name")
                        ))
                        // Exercise the named Java-record fallback with real source metadata, hiding only its KSP members.
                        val owner = if (name == "FallbackHolder") RecordWithoutMembers(declaration) else declaration
                        collector.collectDeclaration(owner, "polymorphic.sites.$name")
                    }
                    return emptyList()
                }
            }
        }
    }

    private class RecordWithoutMembers(declaration: KSClassDeclaration) : KSClassDeclaration by declaration {
        override val declarations: Sequence<KSDeclaration> get() = emptySequence()
    }
}
