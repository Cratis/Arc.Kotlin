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
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeKind
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorPolymorphicPropertyCompilationTest {
    @TempDir
    lateinit var workingDirectory: File

    @Test
    fun `Kotlin and Java safe property uses generate handlers queries and polymorphic metadata`() {
        val result = compile(positiveSources())

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse("[ARCKSP0305]" in result.messages, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.PolymorphicPropertiesArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule
        assertEquals(setOf("KCommand", "JCommand"), module.commandHandlers.map { it.metadata.name }.toSet())
        assertEquals(2, module.queryPerformers.size)
        for (prefix in listOf("K", "J")) {
            val properties = module.commandHandlers.single { it.metadata.name == "${prefix}Command" }
                .metadata.properties.associateBy { it.name }
            assertEquals("polymorphic.safe.${prefix}Shape", properties.getValue("shape").typeName)
            assertEquals("polymorphic.safe.${prefix}Abstract", properties.getValue("abstractValue").typeName)
            assertEquals("polymorphic.safe.${prefix}Leaf", properties.getValue("leaf").typeName)
            assertEquals(TypeShapeKind.SEQUENCE, properties.getValue("leaves").shape.kind)
            assertEquals("polymorphic.safe.${prefix}Leaf", properties.getValue("leaves").shape.elementShape?.typeName)
            val commandType = module.types.single { it.fullyQualifiedName == "polymorphic.safe.${prefix}Command" }
            assertEquals(
                listOf("polymorphic.safe.${prefix}Leaf"),
                commandType.properties.single { it.name == "shape" }.derivatives
            )
            // A concrete superclass with annotated descendants is legal when visited structurally, not as a property use.
            val leaf = module.types.single { it.fullyQualifiedName == "polymorphic.safe.${prefix}Leaf" }
            assertEquals("polymorphic.safe.${prefix}Parent", leaf.baseTypeName)
            assertEquals("${prefix.lowercase()}-leaf", leaf.derivedTypeId)
            assertTrue(module.types.any { it.fullyQualifiedName == "polymorphic.safe.${prefix}Parent" })
            assertTrue(module.types.any { it.fullyQualifiedName == "polymorphic.safe.${prefix}OrdinaryChild" })
        }
        val javaView = module.interfaces.single { it.fullyQualifiedName == "polymorphic.safe.JView" }
        assertEquals(listOf("abstractValue", "shape"), javaView.properties.map { it.name })
        assertEquals(listOf("polymorphic.safe.JLeaf"), javaView.properties.single { it.name == "shape" }.derivatives)
        assertTrue(workingDirectory.resolve("ksp/sources/resources/META-INF/cratis/arc/PolymorphicProperties.json").isFile)
    }

    @Test
    fun `Kotlin sealed base properties preserve direct and nullable sequence and array metadata`() {
        val result = compile(listOf(SourceFile.kotlin("SealedProperties.kt", """
            package polymorphic.sealed
            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.polymorphism.DerivedType
            public sealed class SealedBase
            @DerivedType("sealed-leaf")
            public class SealedLeaf : SealedBase()
            @Command
            public data class ProbeCommand(
                public val base: SealedBase,
                public val sequence: Collection<SealedBase?>?,
                public val array: Array<SealedBase?>?
            ) { public fun handle() { } }
        """.trimIndent())))

        assertSealedPropertyMetadata(result, "polymorphic.sealed.SealedBase")
        val module = generatedModule(result)
        val leaf = module.types.single { it.fullyQualifiedName == "polymorphic.sealed.SealedLeaf" }
        assertEquals("polymorphic.sealed.SealedBase", leaf.baseTypeName)
        assertEquals("sealed-leaf", leaf.derivedTypeId)
    }

    @Test
    fun `Kotlin sealed base properties remain legal when descendant is generated in a later round`() {
        val lateProvider = LateDescendantProvider()
        val result = compile(
            listOf(SourceFile.kotlin("EarlySealedCommand.kt", """
                package polymorphic.late
                import io.cratis.arc.artifacts.Command
                public sealed class LateBase
                @Command
                public data class ProbeCommand(
                    public val base: LateBase,
                    public val sequence: Collection<LateBase?>?,
                    public val array: Array<LateBase?>?
                ) { public fun handle() { } }
            """.trimIndent())),
            additionalProviders = listOf(lateProvider)
        )

        assertTrue(lateProvider.sawBaseWithoutDescendant)
        assertTrue(lateProvider.sawGeneratedDescendant)
        assertTrue(lateProvider.rounds >= 2)
        assertSealedPropertyMetadata(result, "polymorphic.late.LateBase")
        assertEquals("polymorphic.late.LateBase", result.classLoader.loadClass("polymorphic.late.LateLeaf").superclass.name)
    }

    @Test
    fun `annotated Kotlin sealed descendants do not make a concrete property base polymorphic`() {
        val result = compile(listOf(SourceFile.kotlin("SealedDescendant.kt", """
            package polymorphic.sealed
            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.polymorphism.DerivedType
            public open class ConcreteBase
            @DerivedType("sealed-descendant")
            public sealed class SealedDescendant : ConcreteBase()
            public class UnannotatedLeaf : SealedDescendant()
            @Command
            public data class ProbeCommand(public val base: ConcreteBase) { public fun handle() { } }
        """.trimIndent())))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse("[ARCKSP0305]" in result.messages, result.messages)
        val module = generatedModule(result)
        assertEquals("polymorphic.sealed.ConcreteBase", module.commandHandlers.single().metadata.properties.single().typeName)
        // General derivative metadata is intentionally unchanged by the property-use validation correction.
        val descendant = module.types.single { it.fullyQualifiedName == "polymorphic.sealed.SealedDescendant" }
        assertEquals("sealed-descendant", descendant.derivedTypeId)
    }

    @Test
    fun `dependency Kotlin sealed base properties remain legal with a source descendant`() {
        val dependency = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("SealedDependency.kt", """
                package polymorphic.dependency
                public sealed class SealedBase
                public open class Intermediate : SealedBase()
            """.trimIndent()))
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, dependency.exitCode, dependency.messages)
        val result = compile(
            listOf(SourceFile.kotlin("SealedConsumer.kt", """
                package polymorphic.consumer
                import io.cratis.arc.artifacts.Command
                import io.cratis.arc.polymorphism.DerivedType
                import polymorphic.dependency.Intermediate
                import polymorphic.dependency.SealedBase
                @DerivedType("dependency-sealed-leaf")
                public class SealedLeaf : Intermediate()
                @Command
                public data class ProbeCommand(
                    public val base: SealedBase,
                    public val sequence: Collection<SealedBase?>?,
                    public val array: Array<SealedBase?>?
                ) { public fun handle() { } }
            """.trimIndent())),
            additionalClasspaths = listOf(dependency.outputDirectory)
        )

        assertSealedPropertyMetadata(result, "polymorphic.dependency.SealedBase")
        assertEquals("dependency-sealed-leaf", generatedModule(result).types
            .single { it.fullyQualifiedName == "polymorphic.consumer.SealedLeaf" }.derivedTypeId)
    }

    private fun generatedModule(result: JvmCompilationResult): ArcArtifactModule =
        result.classLoader.loadClass("io.cratis.arc.generated.PolymorphicPropertiesArcArtifactModule")
            .getDeclaredConstructor().newInstance() as ArcArtifactModule

    private fun assertSealedPropertyMetadata(result: JvmCompilationResult, baseName: String) {
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse("[ARCKSP0305]" in result.messages, result.messages)
        val module = generatedModule(result)
        val command = module.commandHandlers.single().metadata
        assertEquals("ProbeCommand", command.name)
        val properties = command.properties.associateBy { it.name }
        assertEquals(baseName, properties.getValue("base").typeName)
        assertFalse(properties.getValue("base").shape.nullable)
        for ((name, kind) in listOf("sequence" to SequenceKind.COLLECTION, "array" to SequenceKind.ARRAY)) {
            val shape = properties.getValue(name).shape
            assertEquals(TypeShapeKind.SEQUENCE, shape.kind)
            assertEquals(kind, shape.sequenceKind)
            assertTrue(shape.nullable)
            assertEquals(baseName, shape.elementShape?.typeName)
            assertEquals(true, shape.elementShape?.nullable)
        }
        assertTrue(module.types.any { it.fullyQualifiedName == baseName })
        assertTrue(workingDirectory.resolve("ksp/sources/resources/META-INF/cratis/arc/PolymorphicProperties.json").isFile)
    }

    @Test
    fun `descendant generated in a later round rejects already collected Kotlin command and Java record properties`() {
        val lateProvider = LateDescendantProvider()
        val result = compile(
            listOf(
                SourceFile.kotlin("EarlyCommand.kt", """
                    package polymorphic.late
                    import io.cratis.arc.artifacts.Command
                    public open class LateBase(public val name: String = "base")
                    @Command
                    public data class EarlyCommand(public val value: LateBase) {
                        public fun handle() { }
                    }
                """.trimIndent()),
                SourceFile.java("EarlyReadModel.java", """
                    package polymorphic.late;
                    import io.cratis.arc.artifacts.ReadModel;
                    @ReadModel
                    public record EarlyReadModel(LateBase value) {
                        public static EarlyReadModel find() { return new EarlyReadModel(new LateBase()); }
                    }
                """.trimIndent())
            ),
            additionalProviders = listOf(lateProvider)
        )

        assertTrue(lateProvider.sawBaseWithoutDescendant)
        assertTrue(lateProvider.sawGeneratedDescendant)
        assertTrue(lateProvider.rounds >= 2)
        assertTrue(workingDirectory.resolve("ksp/sources/kotlin/io/cratis/arc/generated/commands")
            .walkTopDown().any { it.name.startsWith("EarlyCommandArcCommandHandler_") })
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (owner in listOf("EarlyCommand", "EarlyReadModel")) {
            assertTrue(diagnostic("polymorphic.late.$owner.value", "polymorphic.late.LateBase") in result.messages, result.messages)
        }
        assertEquals(2, Regex("\\[ARCKSP0305]").findAll(result.messages).count(), result.messages)
    }

    @Test
    fun `dependency concrete base with source descendant is rejected at the property use`() {
        val dependency = KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("DependencyBase.kt", """
                package polymorphic.dependency
                public open class DependencyBase
            """.trimIndent()))
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, dependency.exitCode, dependency.messages)
        val result = compile(
            listOf(SourceFile.kotlin("DependencyCommand.kt", """
                package polymorphic.consumer
                import io.cratis.arc.artifacts.Command
                import io.cratis.arc.polymorphism.DerivedType
                import polymorphic.dependency.DependencyBase
                @DerivedType("dependency-leaf")
                public class DependencyLeaf : DependencyBase()
                @Command
                public data class DependencyCommand(public val value: DependencyBase) {
                    public fun handle() { }
                }
            """.trimIndent())),
            additionalClasspaths = listOf(dependency.outputDirectory)
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(diagnostic("polymorphic.consumer.DependencyCommand.value", "polymorphic.dependency.DependencyBase")
            in result.messages, result.messages)
        assertEquals(1, Regex("\\[ARCKSP0305]").findAll(result.messages).count(), result.messages)
    }

    private fun positiveSources(): List<SourceFile> = listOf(
        SourceFile.kotlin("SafeProperties.kt", """
            package polymorphic.safe
            import io.cratis.arc.artifacts.Command
            import io.cratis.arc.artifacts.ReadModel
            import io.cratis.arc.polymorphism.DerivedType
            public interface KShape
            public abstract class KAbstract
            public open class KParent
            @DerivedType("k-leaf")
            public class KLeaf : KParent(), KShape
            @DerivedType("k-abstract-leaf")
            public class KAbstractLeaf : KAbstract()
            public open class KOrdinary
            public class KOrdinaryChild : KOrdinary()
            public open class KUnrelated
            @DerivedType("k-unrelated")
            public class KUnrelatedLeaf : KUnrelated()
            @Command
            public data class KCommand(
                public val shape: KShape,
                public val abstractValue: KAbstract?,
                public val leaf: KLeaf,
                public val leaves: Array<KLeaf>,
                public val ordinary: KOrdinary,
                public val child: KOrdinaryChild
            ) { public fun handle() { } }
            @ReadModel
            public data class KReadModel(public val shape: KShape, public val abstractValue: KAbstract) {
                public companion object {
                    @JvmStatic
                    public fun find(): KReadModel = KReadModel(KLeaf(), KAbstractLeaf())
                }
            }
        """.trimIndent()),
        javaSource("JShape", "public interface JShape { }"),
        javaSource("JAbstract", "public abstract class JAbstract { }"),
        javaSource("JParent", "public class JParent { }"),
        javaSource("JLeaf", "@DerivedType(id = \"j-leaf\") public final class JLeaf extends JParent implements JShape { }"),
        javaSource("JAbstractLeaf", "@DerivedType(id = \"j-abstract-leaf\") public final class JAbstractLeaf extends JAbstract { }"),
        javaSource("JOrdinary", "public class JOrdinary { }"),
        javaSource("JOrdinaryChild", "public final class JOrdinaryChild extends JOrdinary { }"),
        javaSource("JUnrelated", "public class JUnrelated { }"),
        javaSource("JUnrelatedLeaf", "@DerivedType(id = \"j-unrelated\") public final class JUnrelatedLeaf extends JUnrelated { }"),
        javaSource("JView", "public interface JView { JShape getShape(); JAbstract abstractValue(); }"),
        javaSource("JCommand", """
            @Command
            public record JCommand(JShape shape, JAbstract abstractValue, JLeaf leaf, java.util.List<JLeaf> leaves,
                                   JOrdinary ordinary, JOrdinaryChild child, JView view) {
                public void handle() { }
            }
        """.trimIndent()),
        javaSource("JReadModel", """
            @ReadModel
            public record JReadModel(JShape shape, JAbstract abstractValue) {
                public static JReadModel find() { return new JReadModel(new JLeaf(), new JAbstractLeaf()); }
            }
        """.trimIndent())
    )

    private fun javaSource(name: String, declaration: String): SourceFile = SourceFile.java("$name.java", """
        package polymorphic.safe;
        import io.cratis.arc.artifacts.Command;
        import io.cratis.arc.artifacts.ReadModel;
        import io.cratis.arc.polymorphism.DerivedType;
        $declaration
    """.trimIndent())

    private fun diagnostic(property: String, base: String): String =
        "[ARCKSP0305] Artifact/property '$property' declares concrete polymorphic base '$base' " +
            "with visible @DerivedType descendants; declare an interface or abstract base instead."

    private fun compile(
        sources: List<SourceFile>,
        additionalProviders: List<SymbolProcessorProvider> = emptyList(),
        additionalClasspaths: List<File> = emptyList()
    ): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources
        workingDir = workingDirectory
        inheritClassPath = true
        classpaths = additionalClasspaths
        symbolProcessorProviders = (listOf(ArcSymbolProcessorProvider()) + additionalProviders).toMutableList()
        kspProcessorOptions = mutableMapOf("arc.moduleName" to "PolymorphicProperties")
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()

    private class LateDescendantProvider : SymbolProcessorProvider {
        var rounds = 0
        var sawBaseWithoutDescendant = false
        var sawGeneratedDescendant = false

        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
            override fun process(resolver: Resolver): List<KSAnnotated> {
                rounds++
                val descendant = resolver.getClassDeclarationByName(resolver.getKSNameFromString("polymorphic.late.LateLeaf"))
                if (rounds == 1) {
                    sawBaseWithoutDescendant = descendant == null && resolver.getClassDeclarationByName(
                        resolver.getKSNameFromString("polymorphic.late.LateBase")
                    ) != null
                    environment.codeGenerator.createNewFile(
                        Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()),
                        "polymorphic.late",
                        "LateLeaf"
                    ).bufferedWriter().use { writer ->
                        writer.write("""
                            package polymorphic.late
                            import io.cratis.arc.polymorphism.DerivedType
                            @DerivedType("late-leaf")
                            public class LateLeaf : LateBase()
                        """.trimIndent())
                    }
                } else if (descendant != null) {
                    sawGeneratedDescendant = true
                }
                return emptyList()
            }
        }
    }
}
