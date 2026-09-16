// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.json.ArcObjectMapper
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorIdentityCompilationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `Kotlin and ordinary Java concrete providers and factories export only their details graph`() {
        val result = compile(listOf(
            SourceFile.kotlin("Details.kt", """
                package identity
                import io.cratis.arc.identity.*
                public data class Nested(val value: String)
                public data class KotlinDetails(val nested: Nested)
                public data class FactoryDetails(val source: String)
                public data class MemberDetails(val source: String)
                public data class CompanionDetails(val source: String)
                public sealed class Base<T : Any> : IdentityDetailsProvider<T>
                public abstract class Middle<U : Any> : Base<U>()
                public class Provider : Middle<KotlinDetails>() {
                    override val detailsType = KotlinDetails::class.java
                    override suspend fun provide(context: IdentityProviderContext) = IdentityDetails(true, KotlinDetails(Nested("value")))
                }
                public fun factory(): IdentityDetailsProvider<FactoryDetails> = object : IdentityDetailsProvider<FactoryDetails> {
                    override val detailsType = FactoryDetails::class.java
                    override suspend fun provide(context: IdentityProviderContext) = IdentityDetails(true, FactoryDetails("factory"))
                }
                public class Configuration {
                    public fun member(): IdentityDetailsProvider<MemberDetails> = object : IdentityDetailsProvider<MemberDetails> {
                        override val detailsType = MemberDetails::class.java
                        override suspend fun provide(context: IdentityProviderContext) = IdentityDetails(true, MemberDetails("member"))
                    }
                    public companion object {
                        public fun companion(): IdentityDetailsProvider<CompanionDetails> = object : IdentityDetailsProvider<CompanionDetails> {
                            override val detailsType = CompanionDetails::class.java
                            override suspend fun provide(context: IdentityProviderContext) = IdentityDetails(true, CompanionDetails("companion"))
                        }
                    }
                }
            """.trimIndent()),
            SourceFile.java("JavaDetails.java", "package identity; public record JavaDetails(String name) {}"),
            SourceFile.java("JavaFactoryDetails.java", "package identity; public record JavaFactoryDetails(String name) {}"),
            SourceFile.java("JavaMemberDetails.java", "package identity; public record JavaMemberDetails(String name) {}"),
            SourceFile.java("JavaProvider.java", """
                package identity;
                import io.cratis.arc.identity.*;
                import java.util.concurrent.*;
                abstract class JavaBase<T> implements AsyncIdentityDetailsProvider<T> {}
                abstract class JavaMiddle<U> extends JavaBase<U> {}
                public final class JavaProvider extends JavaMiddle<JavaDetails> {
                    public Class<JavaDetails> getDetailsType() { return JavaDetails.class; }
                    public CompletionStage<IdentityDetails<JavaDetails>> provide(IdentityProviderContext context) {
                        return CompletableFuture.completedFuture(new IdentityDetails<>(true, new JavaDetails("java")));
                    }
                    public static AsyncIdentityDetailsProvider<JavaFactoryDetails> factory() {
                        return new AsyncIdentityDetailsProvider<JavaFactoryDetails>() {
                            public Class<JavaFactoryDetails> getDetailsType() { return JavaFactoryDetails.class; }
                            public CompletionStage<IdentityDetails<JavaFactoryDetails>> provide(IdentityProviderContext context) {
                                return CompletableFuture.completedFuture(new IdentityDetails<>(true, new JavaFactoryDetails("factory")));
                            }
                        };
                    }
                    public AsyncIdentityDetailsProvider<JavaMemberDetails> member() {
                        return new AsyncIdentityDetailsProvider<JavaMemberDetails>() {
                            public Class<JavaMemberDetails> getDetailsType() { return JavaMemberDetails.class; }
                            public CompletionStage<IdentityDetails<JavaMemberDetails>> provide(IdentityProviderContext context) {
                                return CompletableFuture.completedFuture(new IdentityDetails<>(true, new JavaMemberDetails("member")));
                            }
                        };
                    }
                }
            """.trimIndent())
        ))
        // Each factory shape owns an otherwise unreachable root, so another factory cannot mask an omission.
        assertGraph(result, setOf("Nested", "KotlinDetails", "FactoryDetails", "MemberDetails", "CompanionDetails",
            "JavaDetails", "JavaFactoryDetails", "JavaMemberDetails"))
        // Unlike a symbol-only Java fixture, these classes must actually be emitted by javac.
        assertEquals("identity.JavaProvider", result.classLoader.loadClass("identity.JavaProvider").name)
    }

    @Test
    fun `concrete Java sealed providers and details are not Kotlin abstract templates`() {
        val result = compile(listOf(
            SourceFile.java("SealedJavaDetails.java", """
                package identity;
                public sealed class SealedJavaDetails permits SealedJavaDetails.Child {
                    public final String value;
                    public SealedJavaDetails(String value) { this.value = value; }
                    static final class Child extends SealedJavaDetails { Child() { super("child"); } }
                }
            """.trimIndent()),
            SourceFile.java("SealedJavaProvider.java", """
                package identity;
                import io.cratis.arc.identity.*;
                import java.util.concurrent.*;
                public sealed class SealedJavaProvider implements AsyncIdentityDetailsProvider<SealedJavaDetails>
                    permits SealedJavaProvider.Child {
                    public Class<SealedJavaDetails> getDetailsType() { return SealedJavaDetails.class; }
                    public CompletionStage<IdentityDetails<SealedJavaDetails>> provide(IdentityProviderContext context) {
                        return CompletableFuture.completedFuture(new IdentityDetails<>(true, new SealedJavaDetails("sealed")));
                    }
                    static final class Child extends SealedJavaProvider {}
                }
            """.trimIndent())
        ))
        assertGraph(result, setOf("SealedJavaDetails"))
        assertEquals("identity.SealedJavaProvider", result.classLoader.loadClass("identity.SealedJavaProvider").name)
    }

    @Test
    fun `later round generated details and providers rebuild the identity graph`() {
        val generator = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                private var generated = false
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    if (!generated) {
                        generated = true
                        environment.codeGenerator.createNewFile(Dependencies(false), "identity", "Late").writer().use {
                            it.write("""
                                package identity
                                import io.cratis.arc.identity.*
                                public data class LateDetails(val value: String)
                                public class LateProvider : IdentityDetailsProvider<LateDetails> {
                                    override val detailsType = LateDetails::class.java
                                    override suspend fun provide(context: IdentityProviderContext) = IdentityDetails(true, LateDetails("late"))
                                }
                            """.trimIndent())
                        }
                    }
                    return emptyList()
                }
            }
        }
        val result = compile(listOf(SourceFile.kotlin("Factory.kt", """
            package identity
            public fun late(): io.cratis.arc.identity.IdentityDetailsProvider<LateDetails> = LateProvider()
            public data class ExistingDetails(val value: String)
            public fun existing(): io.cratis.arc.identity.IdentityDetailsProvider<ExistingDetails> = object : io.cratis.arc.identity.IdentityDetailsProvider<ExistingDetails> {
                override val detailsType = ExistingDetails::class.java
                override suspend fun provide(context: io.cratis.arc.identity.IdentityProviderContext) = io.cratis.arc.identity.IdentityDetails(true, ExistingDetails("existing"))
            }
        """.trimIndent())), generator)
        assertGraph(result, setOf("ExistingDetails", "LateDetails"))
    }

    @Test
    fun `later round ordinary Java provider supertype and details are resolved before emission`() {
        val generator = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                private var generated = false
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    if (!generated) {
                        generated = true
                        for ((name, source) in mapOf(
                            "LateJavaDetails" to "public record LateJavaDetails(String value) {}",
                            "LateJavaBase" to "public abstract class LateJavaBase<T> implements io.cratis.arc.identity.AsyncIdentityDetailsProvider<T> {}"
                        )) environment.codeGenerator.createNewFile(Dependencies(false), "identity", name, "java").writer().use {
                            it.write("package identity; $source")
                        }
                    }
                    return emptyList()
                }
            }
        }
        val result = compile(listOf(SourceFile.java("LateJavaProvider.java", """
            package identity;
            import io.cratis.arc.identity.*;
            import java.util.concurrent.*;
            public final class LateJavaProvider extends LateJavaBase<LateJavaDetails> {
                public Class<LateJavaDetails> getDetailsType() { return LateJavaDetails.class; }
                public CompletionStage<IdentityDetails<LateJavaDetails>> provide(IdentityProviderContext context) {
                    return CompletableFuture.completedFuture(new IdentityDetails<>(true, new LateJavaDetails("late")));
                }
            }
        """.trimIndent()),
            SourceFile.java("ExistingJavaDetails.java", "package identity; public record ExistingJavaDetails(String value) {}"),
            SourceFile.java("ExistingJavaFactory.java", """
                package identity;
                import io.cratis.arc.identity.*;
                import java.util.concurrent.*;
                public final class ExistingJavaFactory {
                    public static AsyncIdentityDetailsProvider<ExistingJavaDetails> existing() {
                        return new AsyncIdentityDetailsProvider<ExistingJavaDetails>() {
                            public Class<ExistingJavaDetails> getDetailsType() { return ExistingJavaDetails.class; }
                            public CompletionStage<IdentityDetails<ExistingJavaDetails>> provide(IdentityProviderContext context) {
                                return CompletableFuture.completedFuture(new IdentityDetails<>(true, new ExistingJavaDetails("existing")));
                            }
                        };
                    }
                }
            """.trimIndent())), generator)
        assertGraph(result, setOf("ExistingJavaDetails", "LateJavaDetails"))
        assertEquals("identity.LateJavaProvider", result.classLoader.loadClass("identity.LateJavaProvider").name)
    }

    @Test
    fun `source bindings specialize dependency provider signatures without scanning dependency only providers`() {
        val dependency = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("Dependency.kt", """
                package dependency
                import io.cratis.arc.identity.*
                public abstract class BinaryBase<T : Any> : AsyncIdentityDetailsProvider<T>
                public abstract class BinaryMiddle<T : Any> : BinaryBase<T>()
                public data class BinaryDetails(val value: String)
                public data class UnrelatedDetails(val value: String)
                public class UnrelatedProvider : BinaryBase<UnrelatedDetails>() {
                    override val detailsType = UnrelatedDetails::class.java
                    override fun provide(context: IdentityProviderContext) = java.util.concurrent.CompletableFuture.completedFuture(
                        IdentityDetails(true, UnrelatedDetails("dependency only")))
                }
            """.trimIndent()))
            workingDir = directory.resolve("dependency")
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, dependency.exitCode, dependency.messages)
        val result = compile(listOf(SourceFile.kotlin("Consumer.kt", """
            package identity
            import io.cratis.arc.identity.*
            public data class BoundDetails(val value: String)
            public class BoundProvider : dependency.BinaryMiddle<BoundDetails>() {
                override val detailsType = BoundDetails::class.java
                override fun provide(context: IdentityProviderContext) = java.util.concurrent.CompletableFuture.completedFuture(
                    IdentityDetails(true, BoundDetails("bound")))
            }
            public class BinaryDetailsProvider : dependency.BinaryMiddle<dependency.BinaryDetails>() {
                override val detailsType = dependency.BinaryDetails::class.java
                override fun provide(context: IdentityProviderContext) = java.util.concurrent.CompletableFuture.completedFuture(
                    IdentityDetails(true, dependency.BinaryDetails("binary")))
            }
        """.trimIndent())), classpath = listOf(dependency.outputDirectory))
        assertGraph(result, setOf("BoundDetails", "dependency.BinaryDetails"))
        val module = result.classLoader.loadClass("io.cratis.arc.generated.IdentityRootsArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule
        val property = module.types.single { it.fullyQualifiedName == "dependency.BinaryDetails" }.properties.single()
        assertEquals("value", property.name)
        assertEquals("kotlin.String", property.shape.typeName)
    }

    @Test
    fun `ordinary Java source provider specializes a compiled Java generic base`() {
        val dependency = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.java("JavaBinaryBase.java", """
                    package dependency;
                    public abstract class JavaBinaryBase<T> implements io.cratis.arc.identity.AsyncIdentityDetailsProvider<T> {}
                """.trimIndent()),
                SourceFile.java("JavaBinaryMiddle.java", """
                    package dependency;
                    public abstract class JavaBinaryMiddle<U> extends JavaBinaryBase<U> {}
                """.trimIndent())
            )
            workingDir = directory.resolve("java-dependency")
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, dependency.exitCode, dependency.messages)
        assertEquals("dependency.JavaBinaryMiddle", dependency.classLoader.loadClass("dependency.JavaBinaryMiddle").name)
        val result = compile(listOf(
            SourceFile.java("JavaBoundDetails.java", "package identity; public record JavaBoundDetails(String value) {}"),
            SourceFile.java("JavaBoundProvider.java", """
                package identity;
                import io.cratis.arc.identity.*;
                import java.util.concurrent.*;
                public final class JavaBoundProvider extends dependency.JavaBinaryMiddle<JavaBoundDetails> {
                    public Class<JavaBoundDetails> getDetailsType() { return JavaBoundDetails.class; }
                    public CompletionStage<IdentityDetails<JavaBoundDetails>> provide(IdentityProviderContext context) {
                        return CompletableFuture.completedFuture(new IdentityDetails<>(true, new JavaBoundDetails("bound")));
                    }
                }
            """.trimIndent())
        ), classpath = listOf(dependency.outputDirectory))
        assertGraph(result, setOf("JavaBoundDetails"))
        assertEquals("identity.JavaBoundProvider", result.classLoader.loadClass("identity.JavaBoundProvider").name)
    }

    private fun assertGraph(result: JvmCompilationResult, names: Set<String>) {
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.IdentityRootsArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule
        val manifest = ArcObjectMapper.create().readValue(
            directory.resolve("ksp/sources/resources/META-INF/cratis/arc/IdentityRoots.json"), ArcArtifactManifest::class.java)
        assertEquals(names.map { if ('.' in it) it else "identity.$it" }.toSet(), module.types.map { it.fullyQualifiedName }.toSet())
        val mapper = ArcObjectMapper.create()
        assertEquals(mapper.writeValueAsString(module.types), mapper.writeValueAsString(manifest.types))
        assertEquals(8, manifest.formatVersion)
        assertTrue(module.commandHandlers.isEmpty())
        assertTrue(module.queryPerformers.isEmpty())
    }

    private fun compile(sources: List<SourceFile>, other: SymbolProcessorProvider? = null, classpath: List<File> = emptyList()) = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources
        classpaths = classpath
        workingDir = directory
        inheritClassPath = true
        symbolProcessorProviders = mutableListOf<SymbolProcessorProvider>(ArcSymbolProcessorProvider()).apply { other?.let(::add) }
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "IdentityRoots")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
