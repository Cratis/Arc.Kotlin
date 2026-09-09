// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import com.tschuchort.compiletesting.useKsp2
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.java.AsyncCommandResponseValueHandler
import io.cratis.arc.java.AsyncCommandResponseValueHandlerAdapter
import io.cratis.arc.java.BlockingCommandResponseValueHandler
import io.cratis.arc.java.BlockingCommandResponseValueHandlerAdapter
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.CommandResponseValueDisposition.CLIENT
import io.cratis.arc.metadata.CommandResponseValueDisposition.HANDLED
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorLateResponseHandlersCompilationTest {
    @Test
    fun `early and late Kotlin handlers invoke the same registered instance for initial and witness commands`() {
        listOf(false, true).forEach { late -> verify(late = late) }
    }

    @Test
    fun `late aggregate becomes client then handled without premature ambiguity`() {
        listOf(false, true).forEach { late -> verify(late = late, aggregate = true) }
    }

    @Test
    fun `early and late Java blocking and async handlers preserve runtime handling`() {
        listOf("Blocking", "Async").forEach { java ->
            listOf(false, true).forEach { late -> verify(late = late, java = java, aggregate = true) }
        }
    }

    @Test
    fun `client invalid response graph disappears when finally handled but fails without handler`() {
        verify(late = true, invalidPayload = true)
        val compilation = compile(listOf(SourceFile.kotlin("Initial.kt", initialSource(invalidPayload = true))))
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("[ARCKSP0300]" in result.messages, result.messages)
        assertTrue("late.responses.AuditEntry.invalid" in result.messages, result.messages)
        assertFalse(manifestFile(compilation).exists())
    }

    @Test
    fun `handled response type is retained when independently reachable as command input`() {
        verify(late = true, retainedInput = true)
    }

    @Test
    fun `multiple late handler rounds accumulate validated contributions`() {
        verify(late = true, multipleHandlers = true)
    }

    private fun verify(
        late: Boolean,
        aggregate: Boolean = false,
        java: String? = null,
        invalidPayload: Boolean = false,
        retainedInput: Boolean = false,
        multipleHandlers: Boolean = false
    ) {
        val sources = mutableListOf(SourceFile.kotlin("Initial.kt", initialSource(aggregate, invalidPayload, multipleHandlers)))
        if (retainedInput) sources += SourceFile.kotlin("Input.kt", """
            package late.responses
            @io.cratis.arc.artifacts.Command
            public class Input(public val audit: AuditEntry) { public fun handle(): Unit = Unit }
        """.trimIndent())
        val handler = handlerSource(java)
        if (!late) sources += if (java == null) SourceFile.kotlin("AuditHandler.kt", handler)
            else SourceFile.java("AuditHandler.java", handler)
        val earlyInvokerSeen = mutableListOf<Boolean>()
        val provider = object : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = object : SymbolProcessor {
                private var emitted = false
                private var secondEmitted = false
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val invoker = resolver.getClassDeclarationByName(resolver.getKSNameFromString(
                        "io.cratis.arc.generated.commands.${commandHandlerClassName("late.responses.Initial")}"
                    ))
                    earlyInvokerSeen += invoker != null
                    val dependencies = Dependencies(true, *resolver.getAllFiles().toList().toTypedArray())
                    fun emit(name: String, source: String, extension: String = "kt") {
                        environment.codeGenerator.createNewFile(dependencies, "late.responses", name, extension)
                            .bufferedWriter().use { it.write(source) }
                    }
                    if (!emitted && invoker != null) {
                        emitted = true
                        if (late) emit("AuditHandler", handler, if (java == null) "kt" else "java")
                        emit("Witness", """
                            package late.responses
                            @io.cratis.arc.artifacts.Command
                            public class Witness { public fun handle(): AuditEntry = AuditEntry("witness") }
                        """.trimIndent())
                    }
                    if (multipleHandlers && !secondEmitted && resolver.getClassDeclarationByName(
                        resolver.getKSNameFromString("late.responses.AuditHandler")) != null) {
                        secondEmitted = true
                        emit("OtherHandler", handlerSource(null).replace("Audit", "Other"))
                    }
                    return emptyList()
                }
            }
        }
        val compilation = compile(sources, listOf(provider))
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertFalse(earlyInvokerSeen.first())
        assertTrue(earlyInvokerSeen.any { it })
        val module = result.classLoader.loadClass("io.cratis.arc.generated.LateResponsesArcArtifactModule")
            .getConstructor().newInstance() as ArcArtifactModule
        val mapper = ArcObjectMapper.create()
        val manifest = mapper.readValue(manifestFile(compilation), ArcArtifactManifest::class.java)
        assertEquals(mapper.writeValueAsString(module.commandHandlers.map { it.metadata }), mapper.writeValueAsString(manifest.commands))
        assertEquals(mapper.writeValueAsString(module.types), mapper.writeValueAsString(manifest.types))
        val initial = module.commandHandlers.single { it.metadata.name == "Initial" }
        val witness = module.commandHandlers.single { it.metadata.name == "Witness" }
        assertEquals(if (aggregate) listOf(CLIENT, HANDLED) else if (multipleHandlers) listOf(HANDLED, HANDLED)
            else listOf(HANDLED), initial.metadata.responseValues.map { it.disposition })
        assertEquals(listOf(HANDLED), witness.metadata.responseValues.map { it.disposition })
        assertEquals(retainedInput, module.types.any { it.name == "AuditEntry" })
        assertFalse(module.types.any { it.name == "OtherEntry" })
        val auditType = result.classLoader.loadClass("late.responses.AuditEntry")
        val handlerType = result.classLoader.loadClass("late.responses.AuditHandler")
        val handlerInstance = handlerType.getConstructor().newInstance()
        assertSame(auditType, handlerType.getMethod("valueClass").invoke(handlerInstance))
        val responseHandler = when (java) {
            "Blocking" -> BlockingCommandResponseValueHandlerAdapter(handlerInstance as BlockingCommandResponseValueHandler)
            "Async" -> AsyncCommandResponseValueHandlerAdapter(handlerInstance as AsyncCommandResponseValueHandler)
            else -> handlerInstance as CommandResponseValueHandler
        }
        val handlers = mutableListOf(responseHandler)
        if (multipleHandlers) handlers += result.classLoader.loadClass("late.responses.OtherHandler")
            .getConstructor().newInstance() as CommandResponseValueHandler
        val registry = ConcurrentCommandHandlerRegistry()
        module.commandHandlers.forEach(registry::register)
        assertSame(initial, registry.find(initial.commandType))
        val pipeline = DefaultCommandPipeline(registry, responseValueHandlers = handlers)
        val options = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        })
        val execution = runBlocking { pipeline.execute(initial.commandType.getConstructor().newInstance(), options) }
        assertTrue(execution.isSuccess, execution.exceptionMessages.toString())
        if (aggregate) assertEquals("client", execution.response) else assertNull(execution.response)
        assertEquals(1, handlerType.getMethod("invocationCount").invoke(handlerInstance))
        val witnessExecution = runBlocking { pipeline.execute(witness.commandType.getConstructor().newInstance(), options) }
        assertTrue(witnessExecution.isSuccess)
        assertNull(witnessExecution.response)
        assertEquals(2, handlerType.getMethod("invocationCount").invoke(handlerInstance))
    }

    private fun initialSource(aggregate: Boolean = false, invalidPayload: Boolean = false, multipleHandlers: Boolean = false): String {
        val responseType = if (aggregate) "Pair<String, AuditEntry>" else if (multipleHandlers) "Pair<AuditEntry, OtherEntry>" else "AuditEntry"
        val response = if (aggregate) "Pair(\"client\", AuditEntry(\"audit\"))" else if (multipleHandlers)
            "Pair(AuditEntry(\"audit\"), OtherEntry(\"other\"))" else "AuditEntry(\"audit\")"
        return """
            package late.responses
            public class AuditEntry(public val value: String${if (invalidPayload)
                ", public val invalid: List<List<String>> = emptyList()" else ""})
            public class OtherEntry(public val value: String)
            @io.cratis.arc.artifacts.Command
            public class Initial { public fun handle(): $responseType = $response }
        """.trimIndent()
    }

    private fun handlerSource(java: String?): String = if (java == null) """
        package late.responses
        import io.cratis.arc.commands.CommandContext
        import io.cratis.arc.commands.CommandResponseValueHandler
        import io.cratis.arc.commands.HandlesCommandResponseValues
        import io.cratis.arc.results.CommandResult
        @HandlesCommandResponseValues(AuditEntry::class)
        public class AuditHandler : CommandResponseValueHandler {
            private var calls: Int = 0
            public fun valueClass(): Class<*> = AuditEntry::class.java
            public fun invocationCount(): Int = calls
            override fun canHandle(context: CommandContext, value: Any): Boolean = value is AuditEntry
            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                check(value is AuditEntry)
                calls++
                return CommandResult.success(context.correlationId)
            }
        }
    """.trimIndent() else """
        package late.responses;
        import io.cratis.arc.commands.CommandContext;
        import io.cratis.arc.commands.HandlesCommandResponseValues;
        import io.cratis.arc.java.${java}CommandResponseValueHandler;
        import io.cratis.arc.results.CommandResult;
        @HandlesCommandResponseValues({AuditEntry.class})
        public final class AuditHandler implements ${java}CommandResponseValueHandler {
            private int calls;
            public Class<?> valueClass() { return AuditEntry.class; }
            public int invocationCount() { return calls; }
            @Override public boolean canHandle(CommandContext context, Object value) { return value instanceof AuditEntry; }
            @Override public ${if (java == "Async") "java.util.concurrent.CompletionStage<CommandResult<?>>" else "CommandResult<?>"}
            handle(CommandContext context, Object value) {
                if (!(value instanceof AuditEntry)) throw new IllegalArgumentException("Unexpected response");
                calls++;
                return ${if (java == "Async") "java.util.concurrent.CompletableFuture.completedFuture(CommandResult.success(context.getCorrelationId()))"
                    else "CommandResult.success(context.getCorrelationId())"};
            }
        }
    """.trimIndent()

    private fun compile(sources: List<SourceFile>, providers: List<SymbolProcessorProvider> = emptyList()): KotlinCompilation =
        KotlinCompilation().apply {
            useKsp2()
            this.sources = sources
            inheritClassPath = true
            symbolProcessorProviders = (listOf(ArcSymbolProcessorProvider()) + providers).toMutableList()
            kspProcessorOptions = mutableMapOf("arc.moduleName" to "LateResponses")
            kspWithCompilation = true
            messageOutputStream = System.out
        }

    private fun manifestFile(compilation: KotlinCompilation) = compilation.workingDir.resolve(
        "ksp/sources/resources/META-INF/cratis/arc/LateResponses.json")
}
