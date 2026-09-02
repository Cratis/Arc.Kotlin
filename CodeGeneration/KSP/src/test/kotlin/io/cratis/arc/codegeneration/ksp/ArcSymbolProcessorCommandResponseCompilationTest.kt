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
import io.cratis.arc.metadata.CommandResponseValueDisposition
import java.io.File
import java.nio.file.Files
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorCommandResponseCompilationTest {
    @Test
    fun `Kotlin and Java aggregates produce ordered response metadata`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "ResponseFixtures.kt",
                    """
                    package response.fixtures

                    import io.cratis.arc.artifacts.Command
                    import io.cratis.arc.authorization.AuthorizationResult
                    import io.cratis.arc.commands.ArcOneOf
                    import io.cratis.arc.commands.CommandContext
                    import io.cratis.arc.commands.CommandResponseValueHandler
                    import io.cratis.arc.commands.CommandResponseValues
                    import io.cratis.arc.commands.HandlesCommandResponseValues
                    import io.cratis.arc.results.CommandResult
                    import io.cratis.arc.results.ValidationResult
                    import io.cratis.arc.results.ValidationResultSeverity
                    import io.cratis.chronicle.eventSequences.EventForEventSourceId
                    import java.util.UUID

                    public interface CustomHandledContract
                    public data class CustomHandled(public val value: String) : CustomHandledContract
                    public data class KotlinClient(public val value: String)

                    public abstract class DeclarativeResponseHandlerBase : CommandResponseValueHandler {
                        final override fun canHandle(context: CommandContext, value: Any): Boolean =
                            value is CustomHandledContract

                        final override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
                            CommandResult.success(context.correlationId)
                    }

                    @HandlesCommandResponseValues(CustomHandledContract::class)
                    public class DeclarativeResponseHandler : DeclarativeResponseHandlerBase()

                    @Command
                    public class KotlinPairCommand {
                        public fun handle(): Pair<KotlinClient, CustomHandled> =
                            Pair(KotlinClient("client"), CustomHandled("handled"))
                    }

                    @Command
                    public class KotlinNestedCommand {
                        public fun handle(): ArcOneOf<Triple<CustomHandled, Pair<List<KotlinClient>, CustomHandled>, List<ValidationResult>>> =
                            ArcOneOf.of(
                                Triple(
                                    CustomHandled("first"),
                                    Pair(listOf(KotlinClient("client")), CustomHandled("second")),
                                    listOf(ValidationResult(ValidationResultSeverity.Error, "invalid"))
                                )
                            )
                    }

                    @Command
                    public class KotlinHandledOnlyCommand {
                        public fun handle(): Pair<CustomHandled, CustomHandled> =
                            Pair(CustomHandled("one"), CustomHandled("two"))
                    }

                    @Command
                    public class KotlinCustomCollectionCommand {
                        public fun handle(): List<CustomHandled> = listOf(CustomHandled("client collection"))
                    }

                    @Command
                    public class KotlinCustomArrayCommand {
                        public fun handle(): Array<CustomHandled> = arrayOf(CustomHandled("client array"))
                    }

                    @Command
                    public class KotlinNestedCommandResultCommand {
                        public fun handle(): Pair<CommandResult<KotlinClient>, CustomHandled> =
                            Pair(CommandResult.success(UUID.randomUUID(), KotlinClient("client")), CustomHandled("handled"))
                    }

                    @Command
                    public class KotlinValidationArrayCommand {
                        public fun handle(): Pair<Array<ValidationResult>, KotlinClient> = Pair(
                            arrayOf(ValidationResult(ValidationResultSeverity.Error, "invalid")),
                            KotlinClient("client")
                        )
                    }

                    @Command
                    public class KotlinAuthorizationCollectionCommand {
                        public fun handle(): List<AuthorizationResult> = listOf(AuthorizationResult.success())
                    }

                    @Command
                    public class KotlinRoutedEventListCommand {
                        public fun handle(): List<EventForEventSourceId> = emptyList()
                    }

                    @Command
                    public class KotlinRoutedEventArrayCommand {
                        public fun handle(): Array<EventForEventSourceId> = emptyArray()
                    }

                    @Command
                    public class UntypedResponseValuesCommand {
                        public fun handle(): CommandResponseValues =
                            CommandResponseValues.of(CustomHandled("unknown statically"))
                    }

                    @Command
                    public class KotlinUnitCommand {
                        public fun handle(): Unit = Unit
                    }
                    """.trimIndent()
                ),
                SourceFile.kotlin(
                    "EventForEventSourceId.kt",
                    """
                    package io.cratis.chronicle.eventSequences

                    public data class EventForEventSourceId(public val eventSourceId: String, public val event: Any)
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaVoidCommand.java",
                    """
                    package response.fixtures;

                    import io.cratis.arc.artifacts.Command;

                    @Command
                    public final class JavaVoidCommand {
                        public void handle() {
                        }
                    }
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaClient.java",
                    """
                    package response.fixtures;

                    public record JavaClient(String value) {}
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaHandledContract.java",
                    """
                    package response.fixtures;

                    public interface JavaHandledContract {}
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaHandled.java",
                    """
                    package response.fixtures;

                    public record JavaHandled(String value) implements JavaHandledContract {}
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaDeclarativeResponseHandler.java",
                    """
                    package response.fixtures;

                    import io.cratis.arc.commands.CommandContext;
                    import io.cratis.arc.commands.HandlesCommandResponseValues;
                    import io.cratis.arc.java.BlockingCommandResponseValueHandler;
                    import io.cratis.arc.results.CommandResult;

                    @HandlesCommandResponseValues({JavaHandledContract.class})
                    public final class JavaDeclarativeResponseHandler implements BlockingCommandResponseValueHandler {
                        @Override
                        public boolean canHandle(CommandContext context, Object value) {
                            return value instanceof JavaHandledContract;
                        }

                        @Override
                        public CommandResult<?> handle(CommandContext context, Object value) {
                            return CommandResult.success(context.getCorrelationId());
                        }
                    }
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaAsyncDeclarativeResponseHandler.java",
                    """
                    package response.fixtures;

                    import io.cratis.arc.commands.CommandContext;
                    import io.cratis.arc.commands.HandlesCommandResponseValues;
                    import io.cratis.arc.java.AsyncCommandResponseValueHandler;
                    import io.cratis.arc.results.CommandResult;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.CompletionStage;

                    @HandlesCommandResponseValues({JavaHandledContract.class})
                    public final class JavaAsyncDeclarativeResponseHandler implements AsyncCommandResponseValueHandler {
                        @Override
                        public boolean canHandle(CommandContext context, Object value) {
                            return value instanceof JavaHandledContract;
                        }

                        @Override
                        public CompletionStage<CommandResult<?>> handle(CommandContext context, Object value) {
                            return CompletableFuture.completedFuture(CommandResult.success(context.getCorrelationId()));
                        }
                    }
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaPairCommand.java",
                    """
                    package response.fixtures;

                    import io.cratis.arc.artifacts.Command;
                    import kotlin.Pair;

                    @Command
                    public final class JavaPairCommand {
                        public Pair<JavaClient, JavaHandled> handle() {
                            return new Pair<>(new JavaClient("client"), new JavaHandled("handled"));
                        }
                    }
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaNestedCommand.java",
                    """
                    package response.fixtures;

                    import io.cratis.arc.artifacts.Command;
                    import io.cratis.arc.commands.ArcOneOf;
                    import java.util.List;
                    import kotlin.Pair;
                    import kotlin.Triple;

                    @Command
                    public final class JavaNestedCommand {
                        public ArcOneOf<Triple<JavaHandled, Pair<List<JavaClient>, JavaHandled>, JavaHandled>> handle() {
                            return ArcOneOf.of(
                                new Triple<>(
                                    new JavaHandled("first"),
                                    new Pair<>(List.of(new JavaClient("client")), new JavaHandled("second")),
                                    new JavaHandled("third")
                                )
                            );
                        }
                    }
                    """.trimIndent()
                ),
                SourceFile.java(
                    "JavaHandledOnlyCommand.java",
                    """
                    package response.fixtures;

                    import io.cratis.arc.artifacts.Command;
                    import kotlin.Pair;

                    @Command
                    public final class JavaHandledOnlyCommand {
                        public Pair<JavaHandled, JavaHandled> handle() {
                            return new Pair<>(new JavaHandled("first"), new JavaHandled("second"));
                        }
                    }
                    """.trimIndent()
                )
            )
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.ResponseMetadataArcArtifactModule")
            .getDeclaredConstructor()
            .newInstance() as ArcArtifactModule
        val commands = module.commandHandlers.associate { handler -> handler.metadata.name to handler.metadata }

        assertResponseValues(
            commands.getValue("KotlinPairCommand"),
            listOf(
                Triple("response.fixtures.KotlinClient", false, CommandResponseValueDisposition.CLIENT),
                Triple("response.fixtures.CustomHandled", false, CommandResponseValueDisposition.HANDLED)
            ),
            "response.fixtures.KotlinClient",
            false
        )
        assertResponseValues(
            commands.getValue("JavaPairCommand"),
            listOf(
                Triple("response.fixtures.JavaClient", false, CommandResponseValueDisposition.CLIENT),
                Triple("response.fixtures.JavaHandled", false, CommandResponseValueDisposition.HANDLED)
            ),
            "response.fixtures.JavaClient",
            false
        )
        assertResponseValues(
            commands.getValue("JavaNestedCommand"),
            listOf(
                Triple("response.fixtures.JavaHandled", false, CommandResponseValueDisposition.HANDLED),
                Triple("response.fixtures.JavaClient", true, CommandResponseValueDisposition.CLIENT),
                Triple("response.fixtures.JavaHandled", false, CommandResponseValueDisposition.HANDLED),
                Triple("response.fixtures.JavaHandled", false, CommandResponseValueDisposition.HANDLED)
            ),
            "response.fixtures.JavaClient",
            true
        )
        assertResponseValues(
            commands.getValue("JavaHandledOnlyCommand"),
            listOf(
                Triple("response.fixtures.JavaHandled", false, CommandResponseValueDisposition.HANDLED),
                Triple("response.fixtures.JavaHandled", false, CommandResponseValueDisposition.HANDLED)
            ),
            null,
            false
        )
        assertResponseValues(
            commands.getValue("KotlinNestedCommand"),
            listOf(
                Triple("response.fixtures.CustomHandled", false, CommandResponseValueDisposition.HANDLED),
                Triple("response.fixtures.KotlinClient", true, CommandResponseValueDisposition.CLIENT),
                Triple("response.fixtures.CustomHandled", false, CommandResponseValueDisposition.HANDLED),
                Triple("io.cratis.arc.results.ValidationResult", true, CommandResponseValueDisposition.HANDLED)
            ),
            "response.fixtures.KotlinClient",
            true
        )
        assertResponseValues(
            commands.getValue("KotlinHandledOnlyCommand"),
            listOf(
                Triple("response.fixtures.CustomHandled", false, CommandResponseValueDisposition.HANDLED),
                Triple("response.fixtures.CustomHandled", false, CommandResponseValueDisposition.HANDLED)
            ),
            null,
            false
        )
        assertResponseValues(
            commands.getValue("KotlinCustomCollectionCommand"),
            listOf(
                Triple("response.fixtures.CustomHandled", true, CommandResponseValueDisposition.CLIENT)
            ),
            "response.fixtures.CustomHandled",
            true
        )
        assertResponseValues(
            commands.getValue("KotlinCustomArrayCommand"),
            listOf(
                Triple("response.fixtures.CustomHandled", true, CommandResponseValueDisposition.CLIENT)
            ),
            "response.fixtures.CustomHandled",
            true
        )
        assertResponseValues(
            commands.getValue("KotlinNestedCommandResultCommand"),
            listOf(
                Triple("response.fixtures.KotlinClient", false, CommandResponseValueDisposition.CLIENT),
                Triple("response.fixtures.CustomHandled", false, CommandResponseValueDisposition.HANDLED)
            ),
            "response.fixtures.KotlinClient",
            false
        )
        assertResponseValues(
            commands.getValue("KotlinValidationArrayCommand"),
            listOf(
                Triple("io.cratis.arc.results.ValidationResult", true, CommandResponseValueDisposition.HANDLED),
                Triple("response.fixtures.KotlinClient", false, CommandResponseValueDisposition.CLIENT)
            ),
            "response.fixtures.KotlinClient",
            false
        )
        assertResponseValues(
            commands.getValue("KotlinAuthorizationCollectionCommand"),
            listOf(
                Triple("io.cratis.arc.authorization.AuthorizationResult", true, CommandResponseValueDisposition.CLIENT)
            ),
            "io.cratis.arc.authorization.AuthorizationResult",
            true
        )
        listOf("KotlinRoutedEventListCommand", "KotlinRoutedEventArrayCommand").forEach { commandName ->
            assertResponseValues(
                commands.getValue(commandName),
                listOf(
                    Triple(
                        "io.cratis.chronicle.eventSequences.EventForEventSourceId",
                        true,
                        CommandResponseValueDisposition.HANDLED
                    )
                ),
                null,
                false
            )
        }
        assertResponseValues(commands.getValue("UntypedResponseValuesCommand"), emptyList(), null, false)
        assertResponseValues(commands.getValue("KotlinUnitCommand"), emptyList(), null, false)
        assertResponseValues(commands.getValue("JavaVoidCommand"), emptyList(), null, false)
    }

    @Test
    fun `KSP annotation lookup does not expose annotated dependency handlers`() {
        val dependency = KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin(
                    "DependencyResponses.kt",
                    """
                    package response.dependency

                    import io.cratis.arc.commands.CommandContext
                    import io.cratis.arc.commands.CommandResponseValueHandler
                    import io.cratis.arc.commands.HandlesCommandResponseValues
                    import io.cratis.arc.results.CommandResult

                    public interface DependencyHandledContract
                    public data class DependencyHandled(public val value: String) : DependencyHandledContract

                    @HandlesCommandResponseValues(DependencyHandledContract::class)
                    public class DependencyResponseHandler : CommandResponseValueHandler {
                        override fun canHandle(context: CommandContext, value: Any): Boolean =
                            value is DependencyHandledContract

                        override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
                            CommandResult.success(context.correlationId)
                    }
                    """.trimIndent()
                )
            )
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, dependency.exitCode, dependency.messages)

        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "DependencyConsumer.kt",
                    """
                    package response.consumer

                    import io.cratis.arc.artifacts.Command
                    import response.dependency.DependencyHandled

                    public data class DependencyClient(public val value: String)

                    @Command
                    public class DependencyResponseCommand {
                        public fun handle(): Pair<DependencyClient, DependencyHandled> =
                            Pair(DependencyClient("client"), DependencyHandled("handled"))
                    }
                    """.trimIndent()
                )
            ),
            "DependencyResponse",
            listOf(dependency.outputDirectory)
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0109]" in result.messages, result.messages)
        assertTrue("response.consumer.DependencyClient" in result.messages, result.messages)
        assertTrue("response.dependency.DependencyHandled" in result.messages, result.messages)
    }

    @Test
    fun `declarative element contract does not suppress a custom collection client candidate`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "CustomCollectionResponse.kt",
                    """
                    package response.customcollection

                    import io.cratis.arc.artifacts.Command
                    import io.cratis.arc.commands.CommandContext
                    import io.cratis.arc.commands.CommandResponseValueHandler
                    import io.cratis.arc.commands.HandlesCommandResponseValues
                    import io.cratis.arc.results.CommandResult

                    public interface CustomHandledContract
                    public class CustomHandled : CustomHandledContract
                    public class ClientValue

                    @HandlesCommandResponseValues(CustomHandledContract::class)
                    public class CustomHandler : CommandResponseValueHandler {
                        override fun canHandle(context: CommandContext, value: Any): Boolean =
                            value is CustomHandledContract

                        override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
                            CommandResult.success(context.correlationId)
                    }

                    @Command
                    public class CustomCollectionResponseCommand {
                        public fun handle(): Pair<ClientValue, List<CustomHandled>> =
                            Pair(ClientValue(), listOf(CustomHandled()))
                    }
                    """.trimIndent()
                )
            ),
            "CustomCollectionResponse"
        )

        assertAmbiguousDiagnostic(
            result,
            "response.customcollection.CustomCollectionResponseCommand.handle",
            "ClientValue",
            "CustomHandled"
        )
    }

    @Test
    fun `explicit runtime collection contract handles an erased collection`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "ExplicitCollectionResponse.kt",
                    """
                    package response.explicitcollection

                    import io.cratis.arc.artifacts.Command
                    import io.cratis.arc.commands.CommandContext
                    import io.cratis.arc.commands.CommandResponseValueHandler
                    import io.cratis.arc.commands.HandlesCommandResponseValues
                    import io.cratis.arc.results.CommandResult

                    public class CustomHandled

                    @HandlesCommandResponseValues(Collection::class)
                    public class CustomCollectionHandler : CommandResponseValueHandler {
                        override fun canHandle(context: CommandContext, value: Any): Boolean = value is Collection<*>

                        override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
                            CommandResult.success(context.correlationId)
                    }

                    @Command
                    public class ExplicitCollectionResponseCommand {
                        public fun handle(): List<CustomHandled> = listOf(CustomHandled())
                    }
                    """.trimIndent()
                )
            ),
            "ExplicitCollectionResponse"
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val module = result.classLoader.loadClass("io.cratis.arc.generated.ExplicitCollectionResponseArcArtifactModule")
            .getDeclaredConstructor()
            .newInstance() as ArcArtifactModule
        assertResponseValues(
            module.commandHandlers.single().metadata,
            listOf(Triple("response.explicitcollection.CustomHandled", true, CommandResponseValueDisposition.HANDLED)),
            null,
            false
        )
    }

    @Test
    fun `Chronicle event element does not suppress an erased collection client candidate`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "EventType.kt",
                    """
                    package io.cratis.chronicle.events

                    @Target(AnnotationTarget.CLASS)
                    public annotation class EventType
                    """.trimIndent()
                ),
                SourceFile.kotlin(
                    "ChronicleCollectionResponse.kt",
                    """
                    package response.chroniclecollection

                    import io.cratis.arc.artifacts.Command
                    import io.cratis.chronicle.events.EventType

                    public class ClientValue

                    @EventType
                    public class ChronicleEvent

                    @Command
                    public class ChronicleCollectionResponseCommand {
                        public fun handle(): Pair<ClientValue, List<ChronicleEvent>> =
                            Pair(ClientValue(), listOf(ChronicleEvent()))
                    }
                    """.trimIndent()
                )
            ),
            "ChronicleCollectionResponse"
        )

        assertAmbiguousDiagnostic(
            result,
            "response.chroniclecollection.ChronicleCollectionResponseCommand.handle",
            "ClientValue",
            "ChronicleEvent"
        )
    }

    @Test
    fun `annotation on a non-handler declaration reports a stable diagnostic`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "InvalidResponseHandler.kt",
                    """
                    package response.invalid

                    import io.cratis.arc.commands.HandlesCommandResponseValues

                    public class HandledValue

                    @HandlesCommandResponseValues(HandledValue::class)
                    public class MarkerOnlyDeclaration
                    """.trimIndent()
                )
            ),
            "InvalidResponseHandler"
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        val diagnostic = result.messages.lineSequence().single { line ->
            "[ARCKSP0102]" in line && "MarkerOnlyDeclaration" in line
        }
        assertTrue("CommandResponseValueHandler" in diagnostic, diagnostic)
        assertTrue("BlockingCommandResponseValueHandler" in diagnostic, diagnostic)
        assertTrue("AsyncCommandResponseValueHandler" in diagnostic, diagnostic)
    }

    @Test
    fun `manifest bytes and runtime response metadata are deterministic across compilations`() {
        val sources = listOf(
            SourceFile.kotlin(
                "DeterministicResponse.kt",
                """
                package response.deterministic

                import io.cratis.arc.artifacts.Command
                import io.cratis.arc.results.ValidationResult

                public data class ClientValue(public val value: String)

                @Command
                public class DeterministicResponseCommand {
                    public fun handle(): Pair<ValidationResult, ClientValue> = error("not invoked")
                }
                """.trimIndent()
            )
        )
        val firstDirectory = Files.createTempDirectory("arc-response-determinism-first").toFile()
        val secondDirectory = Files.createTempDirectory("arc-response-determinism-second").toFile()
        val first = compile(sources, "DeterministicResponse", workingDirectory = firstDirectory)
        val second = compile(sources, "DeterministicResponse", workingDirectory = secondDirectory)

        assertEquals(KotlinCompilation.ExitCode.OK, first.exitCode, first.messages)
        assertEquals(KotlinCompilation.ExitCode.OK, second.exitCode, second.messages)
        val resourcePath = "ksp/sources/resources/META-INF/cratis/arc/DeterministicResponse.json"
        val firstBytes = firstDirectory.resolve(resourcePath).readBytes()
        val secondBytes = secondDirectory.resolve(resourcePath).readBytes()
        assertTrue(firstBytes.contentEquals(secondBytes))

        listOf(first, second).forEach { compilation ->
            val module = compilation.classLoader
                .loadClass("io.cratis.arc.generated.DeterministicResponseArcArtifactModule")
                .getDeclaredConstructor()
                .newInstance() as ArcArtifactModule
            assertResponseValues(
                module.commandHandlers.single().metadata,
                listOf(
                    Triple("io.cratis.arc.results.ValidationResult", false, CommandResponseValueDisposition.HANDLED),
                    Triple("response.deterministic.ClientValue", false, CommandResponseValueDisposition.CLIENT)
                ),
                "response.deterministic.ClientValue",
                false
            )
        }
    }

    @Test
    fun `ambiguous Kotlin aggregate reports client types in declaration order`() {
        val result = compile(
            listOf(
                SourceFile.kotlin(
                    "AmbiguousKotlin.kt",
                    """
                    package response.ambiguous

                    import io.cratis.arc.artifacts.Command

                    public data class FirstClient(public val value: String)
                    public data class SecondClient(public val value: String)

                    @Command
                    public class AmbiguousKotlinCommand {
                        public fun handle(): Pair<FirstClient, SecondClient> =
                            Pair(FirstClient("first"), SecondClient("second"))
                    }
                    """.trimIndent()
                )
            ),
            "AmbiguousKotlin"
        )

        assertAmbiguousDiagnostic(result, "response.ambiguous.AmbiguousKotlinCommand.handle", "FirstClient", "SecondClient")
    }

    @Test
    fun `ambiguous Java aggregate reports client types in declaration order`() {
        val result = compile(
            listOf(
                SourceFile.java(
                    "FirstJavaClient.java",
                    """
                    package response.ambiguous;

                    public record FirstJavaClient(String value) {}
                    """.trimIndent()
                ),
                SourceFile.java(
                    "SecondJavaClient.java",
                    """
                    package response.ambiguous;

                    public record SecondJavaClient(String value) {}
                    """.trimIndent()
                ),
                SourceFile.java(
                    "AmbiguousJavaCommand.java",
                    """
                    package response.ambiguous;

                    import io.cratis.arc.artifacts.Command;
                    import kotlin.Pair;

                    @Command
                    public final class AmbiguousJavaCommand {
                        public Pair<FirstJavaClient, SecondJavaClient> handle() {
                            return new Pair<>(new FirstJavaClient("first"), new SecondJavaClient("second"));
                        }
                    }
                    """.trimIndent()
                )
            ),
            "AmbiguousJava"
        )

        assertAmbiguousDiagnostic(
            result,
            "response.ambiguous.AmbiguousJavaCommand.handle",
            "FirstJavaClient",
            "SecondJavaClient"
        )
    }

    private fun assertResponseValues(
        descriptor: io.cratis.arc.metadata.CommandDescriptor,
        expected: List<Triple<String, Boolean, CommandResponseValueDisposition>>,
        responseTypeName: String?,
        responseIsEnumerable: Boolean
    ) {
        assertEquals(
            expected,
            descriptor.responseValues.map { value -> Triple(value.typeName, value.isEnumerable, value.disposition) }
        )
        assertEquals(responseTypeName, descriptor.responseTypeName)
        assertEquals(responseIsEnumerable, descriptor.responseIsEnumerable)
    }

    private fun assertAmbiguousDiagnostic(
        result: JvmCompilationResult,
        handlerName: String,
        firstType: String,
        secondType: String
    ) {
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0109]" in result.messages, result.messages)
        val diagnostic = result.messages.lineSequence().single { line -> "[ARCKSP0109]" in line }
        assertTrue(handlerName in diagnostic, diagnostic)
        assertTrue(diagnostic.indexOf(firstType) < diagnostic.indexOf(secondType), diagnostic)
    }

    private fun compile(
        sources: List<SourceFile>,
        moduleName: String = "ResponseMetadata",
        additionalClasspaths: List<File> = emptyList(),
        workingDirectory: File? = null
    ): JvmCompilationResult = KotlinCompilation().apply {
        useKsp2()
        this.sources = sources
        workingDirectory?.let { directory -> workingDir = directory }
        inheritClassPath = true
        classpaths = additionalClasspaths
        symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
        kspProcessorOptions = mutableMapOf("arc.moduleName" to moduleName)
        kspWithCompilation = true
        messageOutputStream = System.out
    }.compile()
}
