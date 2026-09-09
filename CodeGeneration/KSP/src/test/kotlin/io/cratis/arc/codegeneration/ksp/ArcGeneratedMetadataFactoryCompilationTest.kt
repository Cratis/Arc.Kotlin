// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.fasterxml.jackson.databind.JsonNode
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.QueryPerformer
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@OptIn(ExperimentalCompilerApi::class)
internal class ArcGeneratedMetadataFactoryCompilationTest {
    @ParameterizedTest(name = "java={0}, companionFirst={1}")
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `early invoker signatures remain valid before fresh final metadata factories exist`(
        java: Boolean,
        companionFirst: Boolean
    ) {
        val handlerName = commandHandlerClassName("factories.Input")
        val performerName = queryPerformerClassName("factories.View.find")
        val companion = SignatureConsumerProvider(handlerName, performerName)
        val compilation = KotlinCompilation().apply {
            useKsp2()
            sources = fixtureSources(java, handlerName, performerName)
            inheritClassPath = true
            symbolProcessorProviders = if (companionFirst) {
                mutableListOf(companion, ArcSymbolProcessorProvider())
            } else {
                mutableListOf(ArcSymbolProcessorProvider(), companion)
            }
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "Factories")
            kspWithCompilation = true
            jvmTarget = "17"
            javacArguments = mutableListOf("--release", "17", "-Xlint:all", "-Werror")
            allWarningsAsErrors = true
            messageOutputStream = System.out
        }
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(companion.validatedRound >= 2)
        assertTrue(companion.sawLateArtifact)
        assertTrue(companion.finished)

        // These compiled consumers call the concrete generated no-argument constructors in both languages.
        val consumer = result.classLoader.loadClass("factories.DirectConsumer")
        val handler = consumer.getMethod("handler").invoke(null) as CommandHandler
        val otherHandler = consumer.getMethod("handler").invoke(null) as CommandHandler
        val performer = consumer.getMethod("performer").invoke(null) as QueryPerformer
        val otherPerformer = consumer.getMethod("performer").invoke(null) as QueryPerformer
        assertSame(handler.metadata, handler.metadata)
        assertSame(performer.descriptor, performer.descriptor)
        assertNotSame(handler.metadata, otherHandler.metadata)
        assertNotSame(performer.descriptor, otherPerformer.descriptor)
        assertEquals(CommandDescriptor::class.java, handler.javaClass.getMethod("getMetadata").returnType)
        assertEquals(QueryDescriptor::class.java, performer.javaClass.getMethod("getDescriptor").returnType)

        val module = result.classLoader.loadClass("io.cratis.arc.generated.FactoriesArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        assertEquals(listOf("Input", "LateArtifact"), module.commandHandlers.map { it.metadata.name })
        val mapper = ArcObjectMapper.create()
        val resources = compilation.workingDir.resolve("ksp/sources/resources")
        val manifest = mapper.readTree(resources.resolve("META-INF/cratis/arc/Factories.json"))
        val commandJson = mapper.valueToTree<JsonNode>(handler.metadata)
        val queryJson = mapper.valueToTree<JsonNode>(performer.descriptor)
        assertEquals(manifest["commands"].first(), commandJson)
        assertEquals(manifest["queries"].single(), queryJson)
        assertEquals(commandJson, mapper.valueToTree<JsonNode>(module.commandHandlers.first().metadata))
        assertEquals(queryJson, mapper.valueToTree<JsonNode>(module.queryPerformers.single().descriptor))
        assertNotSame(handler.metadata, module.commandHandlers.first().metadata)
        assertNotSame(performer.descriptor, module.queryPerformers.single().descriptor)
        assertEquals("write", handler.metadata.authorization.policy)
        assertEquals(listOf("editor"), handler.metadata.authorization.roles)
        assertEquals(listOf("bearer"), handler.metadata.authorization.schemes)
        assertTrue(handler.metadata.treatWarningsAsErrors)
        assertTrue(handler.metadata.properties.single().isCommandKey)
        assertTrue(handler.metadata.properties.single().validationRules.isNotEmpty())
        assertEquals("/factory/find", performer.descriptor.explicitPath)
        assertEquals(!java, performer.descriptor.parameters.single().hasDefault)
        // Jakarta executable constraints are supported on Kotlin instance queries, not static Java methods.
        assertEquals(!java, performer.descriptor.parameters.single().validationRules.isNotEmpty())
        assertEquals(
            "io.cratis.arc.generated.FactoriesArcArtifactModule\n",
            resources.resolve("META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule").readText()
        )
        val helper = compilation.workingDir.resolve(
            "ksp/sources/kotlin/io/cratis/arc/generated/FactoriesArcArtifactMetadata.kt"
        ).readText()
        assertTrue("internal object FactoriesArcArtifactMetadata" in helper)
        assertTrue("fun create$handlerName(): io.cratis.arc.metadata.CommandDescriptor" in helper)
        assertTrue("fun create$performerName(): io.cratis.arc.metadata.QueryDescriptor" in helper)
        assertFalse("ArcArtifactModule(" in helper)
        assertFalse("$handlerName()" in helper.substringAfter(" = "))
    }

    private fun fixtureSources(java: Boolean, handler: String, performer: String): List<SourceFile> {
        val handlerType = "io.cratis.arc.generated.commands.$handler"
        val performerType = "io.cratis.arc.generated.queries.$performer"
        if (java) return listOf(
            SourceFile.kotlin("PackageMarker.kt", "package factories"),
            SourceFile.java("Input.java", """
                package factories;
                @io.cratis.arc.artifacts.Command
                @io.cratis.arc.artifacts.TreatWarningsAsErrors
                @io.cratis.arc.authorization.Authorize(policy = "write", roles = {"editor"}, schemes = {"bearer"})
                public record Input(@io.cratis.arc.artifacts.CommandKey @jakarta.validation.constraints.NotBlank String id) {
                    public String handle() { return id; }
                }
            """.trimIndent()),
            SourceFile.java("View.java", """
                package factories;
                @io.cratis.arc.artifacts.ReadModel
                public record View(String value) {
                    @io.cratis.arc.queries.Path("/factory/find")
                    @io.cratis.arc.authorization.AllowAnonymous
                    public static View find(String value) { return new View(value); }
                }
            """.trimIndent()),
            SourceFile.java("DirectConsumer.java", """
                package factories;
                public final class DirectConsumer {
                    public static $handlerType handler() { return new $handlerType(); }
                    public static $performerType performer() { return new $performerType(); }
                }
            """.trimIndent())
        )
        return listOf(SourceFile.kotlin("Fixtures.kt", """
            package factories
            @io.cratis.arc.artifacts.Command
            @io.cratis.arc.artifacts.TreatWarningsAsErrors
            @io.cratis.arc.authorization.Authorize(policy = "write", roles = ["editor"], schemes = ["bearer"])
            public data class Input(
                @io.cratis.arc.artifacts.CommandKey
                @field:jakarta.validation.constraints.NotBlank
                public val id: String
            ) {
                public fun handle(): String = id
            }
            @io.cratis.arc.artifacts.ReadModel
            public data class View(public val value: String) {
                public companion object {
                    @JvmStatic
                    @io.cratis.arc.queries.Path("/factory/find")
                    @io.cratis.arc.authorization.AllowAnonymous
                    public fun find(@jakarta.validation.constraints.NotBlank value: String = "default"): View = View(value)
                }
            }
            public object DirectConsumer {
                @JvmStatic public fun handler(): $handlerType = $handlerType()
                @JvmStatic public fun performer(): $performerType = $performerType()
            }
        """.trimIndent()))
    }

    private class SignatureConsumerProvider(
        private val handlerName: String,
        private val performerName: String
    ) : SymbolProcessorProvider {
        var validatedRound = 0
        var sawLateArtifact = false
        var finished = false

        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
            private var round = 0

            override fun process(resolver: Resolver): List<KSAnnotated> {
                round++
                assertNull(resolver.getClassDeclarationByName(
                    resolver.getKSNameFromString("io.cratis.arc.generated.FactoriesArcArtifactMetadata")
                ), "The metadata helper must not exist during process, including the signature-validation round")
                if (validatedRound != 0) {
                    sawLateArtifact = resolver.getClassDeclarationByName(
                        resolver.getKSNameFromString("factories.LateArtifact")
                    ) != null
                    return emptyList()
                }
                val handler = resolver.getClassDeclarationByName(
                    resolver.getKSNameFromString("io.cratis.arc.generated.commands.$handlerName")
                ) ?: return emptyList()
                val performer = resolver.getClassDeclarationByName(
                    resolver.getKSNameFromString("io.cratis.arc.generated.queries.$performerName")
                ) ?: return emptyList()
                validateInvoker(handler, "metadata", "io.cratis.arc.metadata.CommandDescriptor", "invoke",
                    "io.cratis.arc.commands.CommandContext")
                validateInvoker(performer, "descriptor", "io.cratis.arc.metadata.QueryDescriptor", "perform",
                    "io.cratis.arc.queries.QueryContext")
                validatedRound = round
                environment.codeGenerator.createNewFile(
                    Dependencies(true, *resolver.getAllFiles().toList().toTypedArray()), "factories", "LateArtifact"
                ).bufferedWriter().use { writer ->
                    writer.write("""
                        package factories
                        @io.cratis.arc.artifacts.Command
                        public data class LateArtifact(public val value: String) {
                            public fun handle(): String = value
                        }
                    """.trimIndent())
                }
                return emptyList()
            }

            override fun finish() {
                assertTrue(validatedRound > 0, "Early generated invokers must be visible and valid before finish")
                finished = true
            }
        }

        private fun validateInvoker(
            declaration: KSClassDeclaration,
            property: String,
            descriptorType: String,
            method: String,
            contextType: String
        ) {
            // validate() failures and unresolved signature types are fatal, never caught or deferred away.
            assertTrue(declaration.validate(), declaration.qualifiedName?.asString())
            assertTrue(Modifier.PUBLIC in declaration.modifiers)
            assertTrue(declaration.getConstructors().any { it.parameters.isEmpty() && Modifier.PUBLIC in it.modifiers })
            val metadata = declaration.getAllProperties().single { it.simpleName.asString() == property }
            assertTrue(metadata.validate())
            assertFalse(metadata.type.resolve().isError)
            assertEquals(descriptorType, metadata.type.resolve().declaration.qualifiedName?.asString())
            val invocation = declaration.getDeclaredFunctions().single { it.simpleName.asString() == method }
            assertTrue(invocation.validate())
            assertTrue(Modifier.SUSPEND in invocation.modifiers)
            assertEquals(contextType, invocation.parameters.single().type.resolve().declaration.qualifiedName?.asString())
            val returnType = requireNotNull(invocation.returnType).resolve()
            assertFalse(returnType.isError)
            assertEquals("kotlin.Any", returnType.declaration.qualifiedName?.asString())
            assertTrue(returnType.isMarkedNullable)
        }
    }
}
