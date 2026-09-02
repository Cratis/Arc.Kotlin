// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.artifacts.ArcArtifactModuleRegistry
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.chronicle.ChronicleCommandExecutionScope
import io.cratis.arc.chronicle.ChronicleCommandResponseValueHandler
import io.cratis.arc.chronicle.ChronicleCommandTransaction
import io.cratis.arc.contracts.fixtures.AggregateClientResponse
import io.cratis.arc.contracts.fixtures.ChronicleScopedResponseCommand
import io.cratis.arc.contracts.fixtures.HandledResponse
import io.cratis.arc.contracts.fixtures.JavaAggregateClientResponse
import io.cratis.arc.contracts.fixtures.JavaAsyncCommand
import io.cratis.arc.contracts.fixtures.JavaAsyncDependency
import io.cratis.arc.contracts.fixtures.JavaMapMetadataCommand
import io.cratis.arc.contracts.fixtures.JavaOptionalReadModelCommand
import io.cratis.arc.contracts.fixtures.JavaOptionalServiceCommand
import io.cratis.arc.contracts.fixtures.JavaOptionalServiceDependency
import io.cratis.arc.contracts.fixtures.JavaPairResponseCommand
import io.cratis.arc.contracts.fixtures.JavaReadModelCommand
import io.cratis.arc.contracts.fixtures.JavaRoutedEventArrayResponseCommand
import io.cratis.arc.contracts.fixtures.JavaTemporalCommand
import io.cratis.arc.contracts.fixtures.JavaTemporalResult
import io.cratis.arc.contracts.fixtures.KotlinCommandResultResponseCommand
import io.cratis.arc.contracts.fixtures.KotlinHandledOnlyResponseCommand
import io.cratis.arc.contracts.fixtures.KotlinMapMetadataCommand
import io.cratis.arc.contracts.fixtures.KotlinNestedResponseCommand
import io.cratis.arc.contracts.fixtures.KotlinPairResponseCommand
import io.cratis.arc.contracts.fixtures.MetadataEvent
import io.cratis.arc.contracts.fixtures.KotlinNullableReadModelCommand
import io.cratis.arc.contracts.fixtures.KotlinReadModelCommand
import io.cratis.arc.contracts.fixtures.KotlinReadModelCommandWithoutKey
import io.cratis.arc.contracts.fixtures.KotlinRegularCommand
import io.cratis.arc.contracts.fixtures.KotlinRegularDependency
import io.cratis.arc.contracts.fixtures.KotlinRoutedEventListResponseCommand
import io.cratis.arc.contracts.fixtures.KotlinSuspendCommand
import io.cratis.arc.contracts.fixtures.KotlinSuspendDependency
import io.cratis.arc.contracts.fixtures.KotlinTemporalCommand
import io.cratis.arc.contracts.fixtures.KotlinTemporalResult
import io.cratis.arc.contracts.fixtures.FirstProvidedValue
import io.cratis.arc.contracts.fixtures.ProvideCommand
import io.cratis.arc.contracts.fixtures.ProvideFallback
import io.cratis.arc.contracts.fixtures.ProvideFixtureService
import io.cratis.arc.contracts.fixtures.RoutedEventListResponseMode
import io.cratis.arc.contracts.fixtures.RoutedEventResponseCommand
import io.cratis.arc.contracts.fixtures.SecondProvidedValue
import io.cratis.arc.contracts.fixtures.CommandReadModel
import io.cratis.arc.generated.ContractTestsArcArtifactModule
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.queries.BlockingReadModelForCommandResolver
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.ReadModelForCommandOwnership
import io.cratis.arc.queries.ReadModelForCommandResolverRegistry
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.eventSequences.AppendOptions
import io.cratis.chronicle.eventSequences.AppendResult
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.eventSequences.IEventLog
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalTime
import java.util.ServiceLoader
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GeneratedCommandHandlersTest {
    @Test
    fun `generated module is available explicitly and through ServiceLoader`() {
        val explicit = ContractTestsArcArtifactModule()
        val loaded = ServiceLoader.load(ArcArtifactModule::class.java).toList()

        assertEquals(25, explicit.commandHandlers.size)
        assertEquals(23, explicit.queryPerformers.size)
        assertEquals(listOf(ContractTestsArcArtifactModule::class.java), loaded.map(Any::javaClass))
        assertEquals(
            listOf(
                ChronicleScopedResponseCommand::class.java.name,
                "io.cratis.arc.contracts.fixtures.EventCommand",
                JavaAsyncCommand::class.java.name,
                JavaMapMetadataCommand::class.java.name,
                JavaOptionalReadModelCommand::class.java.name,
                JavaOptionalServiceCommand::class.java.name,
                JavaPairResponseCommand::class.java.name,
                JavaReadModelCommand::class.java.name,
                JavaRoutedEventArrayResponseCommand::class.java.name,
                JavaTemporalCommand::class.java.name,
                KotlinCommandResultResponseCommand::class.java.name,
                KotlinHandledOnlyResponseCommand::class.java.name,
                KotlinMapMetadataCommand::class.java.name,
                KotlinNestedResponseCommand::class.java.name,
                KotlinNullableReadModelCommand::class.java.name,
                KotlinPairResponseCommand::class.java.name,
                KotlinReadModelCommand::class.java.name,
                KotlinReadModelCommandWithoutKey::class.java.name,
                KotlinRegularCommand::class.java.name,
                KotlinRoutedEventListResponseCommand::class.java.name,
                KotlinSuspendCommand::class.java.name,
                KotlinTemporalCommand::class.java.name,
                "io.cratis.arc.contracts.fixtures.MetadataCommand",
                ProvideCommand::class.java.name,
                RoutedEventResponseCommand::class.java.name
            ),
            explicit.commandHandlers.map { handler -> handler.commandType.name }
        )
    }

    @Test
    fun `generated Kotlin regular handler executes through the real pipeline`() = runBlocking {
        val dependency = KotlinRegularDependency()
        val pipeline = createPipeline()

        val result = pipeline.execute(
            KotlinRegularCommand("regular-id", "hello", null),
            options(mapOf(KotlinRegularDependency::class.java to dependency))
        )

        assertTrue(result.isSuccess)
        assertEquals("regular:hello", result.response)
        assertEquals(1, dependency.invocationCount)

        val missingOrdinaryService = pipeline.execute(
            KotlinRegularCommand("regular-id", "hello", null),
            options(emptyMap())
        )
        assertFalse(missingOrdinaryService.isSuccess)
        assertTrue(missingOrdinaryService.validationResults.isEmpty())
        assertTrue(missingOrdinaryService.exceptionMessages.isNotEmpty())
    }

    @Test
    fun `generated Kotlin and Java handlers resolve command read models through ownership arbitration and tenant context`() =
        runBlocking {
            val observedKeys = mutableListOf<Any>()
            val observedTenants = mutableListOf<Pair<String?, String?>>()
            var fallbackInvocations = 0
            val declared = object : BlockingReadModelForCommandResolver {
                override fun readModelTypes(): Set<Class<*>> = setOf(CommandReadModel::class.java)
                override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
                override fun resolveBlocking(
                    readModelType: Class<*>,
                    commandContext: CommandContext,
                    key: Any
                ): Any {
                    observedKeys += key
                    observedTenants += commandContext.tenantId to commandContext.tenantNamespace
                    return CommandReadModel(key.toString(), "stored-$key")
                }
            }
            val fallback = object : BlockingReadModelForCommandResolver {
                override fun readModelTypes(): Set<Class<*>> = setOf(CommandReadModel::class.java)
                override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.FALLBACK
                override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any {
                    fallbackInvocations++
                    return CommandReadModel(key.toString(), "fallback")
                }
            }
            val registry = ReadModelForCommandResolverRegistry(listOf(fallback, declared))
            val pipeline = createPipeline()
            val services = mapOf<Class<*>, Any>(
                ReadModelForCommandResolverRegistry::class.java to registry,
                CommandReadModel::class.java to CommandReadModel("service", "must-not-shadow-owner")
            )
            val options = CommandExecutionOptions(
                UUID.randomUUID(),
                ArcPrincipal.anonymous(),
                MapServiceResolver(services),
                "tenant-a",
                "namespace-a"
            )

            val kotlin = pipeline.execute(KotlinReadModelCommand("kotlin-id"), options)
            val java = pipeline.execute(JavaReadModelCommand("java-id"), options)

            assertTrue(kotlin.isSuccess)
            assertEquals("kotlin:stored-kotlin-id", kotlin.response)
            assertTrue(java.isSuccess)
            assertEquals("java:stored-java-id", java.response)
            assertEquals(listOf("kotlin-id", "java-id"), observedKeys)
            assertEquals(listOf("tenant-a" to "namespace-a", "tenant-a" to "namespace-a"), observedTenants)
            assertEquals(0, fallbackInvocations)
        }

    @Test
    fun `generated required nullable and Optional read model parameters use deterministic owned absence semantics`() = runBlocking {
        val missing = object : BlockingReadModelForCommandResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(CommandReadModel::class.java)
            override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
            override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? = null
        }
        val registry = ReadModelForCommandResolverRegistry(listOf(missing))
        val services = mapOf<Class<*>, Any>(
            ReadModelForCommandResolverRegistry::class.java to registry,
            CommandReadModel::class.java to CommandReadModel("service", "must-not-mask-missing-row")
        )
        val pipeline = createPipeline()

        val kotlinRequired = pipeline.execute(KotlinReadModelCommand("missing-kotlin"), options(services))
        val javaRequired = pipeline.execute(JavaReadModelCommand("missing-java"), options(services))
        val missingKey = pipeline.execute(KotlinReadModelCommandWithoutKey("missing-key"), options(services))
        val kotlinNullable = pipeline.execute(KotlinNullableReadModelCommand("nullable"), options(services))
        val javaOptional = pipeline.execute(JavaOptionalReadModelCommand("optional"), options(services))
        val javaProvidedOptional = pipeline.execute(JavaOptionalReadModelCommand("provided"), options(services))

        listOf(kotlinRequired, javaRequired, missingKey).forEach { result ->
            assertFalse(result.isSuccess)
            assertEquals(1, result.validationResults.size)
            assertEquals(ValidationResultReasons.DEPENDENCY_UNAVAILABLE, result.validationResults.single().reason)
            assertTrue(result.exceptionMessages.isEmpty())
        }
        assertTrue(kotlinNullable.isSuccess)
        assertEquals("kotlin:none", kotlinNullable.response)
        assertTrue(javaOptional.isSuccess)
        assertEquals("java:none", javaOptional.response)
        assertTrue(javaProvidedOptional.isSuccess)
        assertEquals("java:provided", javaProvidedOptional.response)

        val failingRegistry = ReadModelForCommandResolverRegistry(
            listOf(object : BlockingReadModelForCommandResolver {
                override fun readModelTypes(): Set<Class<*>> = setOf(CommandReadModel::class.java)
                override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
                override fun resolveBlocking(
                    readModelType: Class<*>,
                    commandContext: CommandContext,
                    key: Any
                ): Any? = throw IllegalStateException("storage unavailable")
            })
        )
        val resolverFailure = pipeline.execute(
            KotlinReadModelCommand("failure"),
            options(mapOf(ReadModelForCommandResolverRegistry::class.java to failingRegistry))
        )
        assertFalse(resolverFailure.isSuccess)
        assertTrue(resolverFailure.validationResults.isEmpty())
        assertTrue(resolverFailure.exceptionMessages.any { it.contains("storage unavailable") })
    }

    @Test
    fun `generated Java Optional handler composes provided values and ordinary services without hiding missing dependencies`() =
        runBlocking {
            val pipeline = createPipeline()
            val service = JavaOptionalServiceDependency("service")

            val serviceResolved = pipeline.execute(
                JavaOptionalServiceCommand("service"),
                options(mapOf(JavaOptionalServiceDependency::class.java to service))
            )
            val providedResolved = pipeline.execute(
                JavaOptionalServiceCommand("provided"),
                options(mapOf(JavaOptionalServiceDependency::class.java to service))
            )
            val missing = pipeline.execute(JavaOptionalServiceCommand("missing"), options(emptyMap()))

            assertTrue(serviceResolved.isSuccess)
            assertEquals("service", serviceResolved.response)
            assertTrue(providedResolved.isSuccess)
            assertEquals("provided", providedResolved.response)
            assertFalse(missing.isSuccess)
            assertTrue(
                missing.exceptionMessages.any { message ->
                    JavaOptionalServiceDependency::class.java.name in message &&
                        java.util.Optional::class.java.name !in message
                }
            )
        }

    @Test
    fun `generated Kotlin suspend and Java CompletionStage handlers execute without reflection`() = runBlocking {
        val suspendDependency = KotlinSuspendDependency()
        val javaDependency = JavaAsyncDependency()
        val services = mapOf<Class<*>, Any>(
            KotlinSuspendDependency::class.java to suspendDependency,
            JavaAsyncDependency::class.java to javaDependency
        )
        val pipeline = createPipeline()

        val suspendResult = pipeline.execute(KotlinSuspendCommand("suspend-id", "note"), options(services))
        val javaResult = pipeline.execute(JavaAsyncCommand("java-id", "value"), options(services))

        assertTrue(suspendResult.isSuccess)
        assertEquals("suspend:suspend-id", suspendResult.response)
        assertEquals(1, suspendDependency.invocationCount)
        assertTrue(javaResult.isSuccess)
        assertEquals("java:value", javaResult.response)
        assertEquals(1, javaDependency.invocationCount)
    }

    @Test
    fun `generated Kotlin and Java temporal commands preserve typed values through the real pipeline`() = runBlocking {
        val kotlinIdentifier = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val kotlinDate = LocalDate.of(2026, 8, 30)
        val kotlinTime = LocalTime.of(12, 30, 45)
        val javaIdentifier = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val javaDate = LocalDate.of(2026, 8, 31)
        val javaTime = LocalTime.of(13, 45, 15)
        val pipeline = createPipeline()

        val kotlinResult = pipeline.execute(
            KotlinTemporalCommand(kotlinIdentifier, kotlinDate, kotlinTime),
            options(emptyMap())
        )
        val javaResult = pipeline.execute(
            JavaTemporalCommand(javaIdentifier, javaDate, javaTime),
            options(emptyMap())
        )

        assertTrue(kotlinResult.isSuccess)
        assertEquals(KotlinTemporalResult(kotlinIdentifier, kotlinDate, kotlinTime), kotlinResult.response)
        assertTrue(javaResult.isSuccess)
        assertEquals(JavaTemporalResult(javaIdentifier, javaDate, javaTime), javaResult.response)
    }

    @Test
    fun `provide values feed handle and remaining arguments fall back to services`() = runBlocking {
        val provider = ProvideFixtureService { value ->
            Pair(FirstProvidedValue("first-$value"), SecondProvidedValue("second-$value"))
        }
        val result = createPipeline().execute(
            ProvideCommand("one"),
            options(
                mapOf(
                    ProvideFixtureService::class.java to provider,
                    ProvideFallback::class.java to ProvideFallback("service")
                )
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("first-one:second-one:service", result.response)
    }

    @Test
    fun `provide control results short circuit and validate never invokes provide`() = runBlocking {
        var invocations = 0
        val validationProvider = ProvideFixtureService {
            invocations++
            ValidationResult(ValidationResultSeverity.Error, "provided rejection")
        }
        val pipeline = createPipeline()
        val services = mapOf<Class<*>, Any>(ProvideFixtureService::class.java to validationProvider)

        val validationOnly = pipeline.validate(ProvideCommand("validate"), options(services))
        val rejected = pipeline.execute(ProvideCommand("execute"), options(services))

        assertTrue(validationOnly.isSuccess)
        assertEquals(1, invocations)
        assertEquals(listOf("provided rejection"), rejected.validationResults.map { it.message })

        val unauthorized = pipeline.execute(
            ProvideCommand("auth"),
            options(mapOf(ProvideFixtureService::class.java to ProvideFixtureService { AuthorizationResult.failure("denied") }))
        )
        assertFalse(unauthorized.isAuthorized)
        assertEquals("denied", unauthorized.authorizationFailureReason)
    }

    @Test
    fun `missing handler dependency is a deterministic command result`() = runBlocking {
        val provider = ProvideFixtureService {
            Pair(FirstProvidedValue("first"), SecondProvidedValue("second"))
        }
        val result = createPipeline().execute(
            ProvideCommand("missing"),
            options(mapOf(ProvideFixtureService::class.java to provider))
        )

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionMessages.single().contains("ProvideFallback"))
        assertTrue(result.exceptionMessages.single().contains("fallback"))
    }

    @Test
    fun `generated singleton handler keeps concurrent provide values request local`() = runBlocking {
        val provider = ProvideFixtureService { value ->
            Pair(FirstProvidedValue("first-$value"), SecondProvidedValue("second-$value"))
        }
        val services = mapOf<Class<*>, Any>(
            ProvideFixtureService::class.java to provider,
            ProvideFallback::class.java to ProvideFallback("service")
        )
        val pipeline = createPipeline()

        val responses = coroutineScope {
            (1..32).map { index ->
                async { pipeline.execute(ProvideCommand(index.toString()), options(services)).response }
            }.awaitAll()
        }

        assertEquals((1..32).map { "first-$it:second-$it:service" }, responses)
    }

    @Test
    fun `generated Kotlin and Java aggregate responses run handlers and return one client value`() = runBlocking {
        val handledEvents = mutableListOf<Any>()
        val handledCustomValues = mutableListOf<Any>()
        val pipeline = createPipeline(
            listOf(
                recordingHandler(MetadataEvent::class.java, handledEvents),
                recordingHandler(HandledResponse::class.java, handledCustomValues)
            )
        )

        val kotlinResult = pipeline.execute(KotlinPairResponseCommand(), options(emptyMap()))
        val javaResult = pipeline.execute(JavaPairResponseCommand(), options(emptyMap()))
        val handledOnlyResult = pipeline.execute(KotlinHandledOnlyResponseCommand(), options(emptyMap()))

        assertTrue(kotlinResult.isSuccess)
        assertEquals(AggregateClientResponse("client"), kotlinResult.response)
        assertEquals(listOf(MetadataEvent("event")), handledEvents)
        assertTrue(javaResult.isSuccess)
        assertEquals(JavaAggregateClientResponse("client"), javaResult.response)
        assertEquals(listOf(HandledResponse("handled")), handledCustomValues.take(1))
        assertTrue(handledOnlyResult.isSuccess)
        assertEquals(null, handledOnlyResult.response)
        assertEquals(
            listOf(HandledResponse("handled"), HandledResponse("first"), HandledResponse("second")),
            handledCustomValues
        )
    }

    @Test
    fun `generated routed collections execute through Chronicle transaction without client leakage`() = runBlocking {
        val eventLog = mockk<IEventLog>()
        val eventStore = mockk<IEventStore>()
        every { eventStore.namespace } returns "default"
        every { eventStore.eventLog } returns eventLog
        val commandHandlers = createCommandHandlers()
        val transactions = ChronicleCommandTransaction()
        val pipeline = DefaultCommandPipeline(
            commandHandlers,
            executionScopes = listOf(ChronicleCommandExecutionScope(transactions)),
            responseValueHandlers = listOf(
                ChronicleCommandResponseValueHandler(eventStore, commandHandlers, transactions)
            )
        )
        val kotlinCorrelationId = UUID.randomUUID()
        val javaCorrelationId = UUID.randomUUID()
        val kotlinEvents = listOf(
            EventForEventSourceId("kotlin-list", MetadataEvent("kotlin-list"))
        )
        val javaEvents = listOf(
            EventForEventSourceId("java-array", MetadataEvent("java-array"))
        )
        fun matchesDecoratedEvents(
            actual: List<EventForEventSourceId>,
            expected: List<EventForEventSourceId>,
            commandType: String
        ): Boolean = actual.size == expected.size && actual.zip(expected).all { (decorated, plain) ->
            decorated.copy(causation = emptyList()) == plain &&
                decorated.causation.singleOrNull()?.properties?.get("commandType") == commandType
        }
        coEvery {
            eventLog.appendMany(
                match<List<EventForEventSourceId>> {
                    matchesDecoratedEvents(it, kotlinEvents, "KotlinRoutedEventListResponseCommand")
                },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                match<UUID> { it == kotlinCorrelationId }
            )
        } returns listOf(successfulAppend(1))
        coEvery {
            eventLog.appendMany(
                match<List<EventForEventSourceId>> {
                    matchesDecoratedEvents(it, javaEvents, "JavaRoutedEventArrayResponseCommand")
                },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                match<UUID> { it == javaCorrelationId }
            )
        } returns listOf(successfulAppend(2))

        val kotlinResult = pipeline.execute(
            KotlinRoutedEventListResponseCommand(RoutedEventListResponseMode.Valid),
            options(emptyMap(), kotlinCorrelationId)
        )
        val javaResult = pipeline.execute(
            JavaRoutedEventArrayResponseCommand(),
            options(emptyMap(), javaCorrelationId)
        )
        val malformedResult = pipeline.execute(
            KotlinRoutedEventListResponseCommand(RoutedEventListResponseMode.Malformed),
            options(emptyMap())
        )
        val emptyResult = pipeline.execute(
            KotlinRoutedEventListResponseCommand(RoutedEventListResponseMode.Empty),
            options(emptyMap())
        )

        assertTrue(kotlinResult.isSuccess)
        assertNull(kotlinResult.response)
        assertTrue(javaResult.isSuccess)
        assertNull(javaResult.response)
        assertFalse(malformedResult.isSuccess)
        assertNull(malformedResult.response)
        assertEquals(ValidationResultReasons.RULE, malformedResult.validationResults.single().reason)
        assertTrue(emptyResult.isSuccess)
        assertNull(emptyResult.response)
        coVerify(exactly = 1) {
            eventLog.appendMany(
                match<List<EventForEventSourceId>> {
                    matchesDecoratedEvents(it, kotlinEvents, "KotlinRoutedEventListResponseCommand")
                },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                match<UUID> { it == kotlinCorrelationId }
            )
        }
        coVerify(exactly = 1) {
            eventLog.appendMany(
                match<List<EventForEventSourceId>> {
                    matchesDecoratedEvents(it, javaEvents, "JavaRoutedEventArrayResponseCommand")
                },
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                match<UUID> { it == javaCorrelationId }
            )
        }
        coVerify(exactly = 2) {
            eventLog.appendMany(
                any<List<EventForEventSourceId>>(),
                match<Map<String, ConcurrencyScope>> { it.isEmpty() },
                any<UUID>()
            )
        }
        coVerify(exactly = 0) {
            eventLog.append(any<String>(), any<Any>(), any<AppendOptions>())
        }
        coVerify(exactly = 0) {
            eventLog.appendMany(any<String>(), any<List<Any>>(), any<AppendOptions>())
        }
    }

    @Test
    fun `generated Kotlin and Java map command handlers execute through the real pipeline`() = runBlocking {
        val pipeline = createPipeline()
        val kotlin = KotlinMapMetadataCommand(
            mapOf("language" to "kotlin"),
            mapOf("values" to listOf(1, 2)),
            mapOf("flags" to mapOf("ready" to true)),
            null
        )
        val java = JavaMapMetadataCommand(
            mapOf("language" to "java"),
            mapOf("values" to listOf(3, 4)),
            mapOf("flags" to mapOf("ready" to true)),
            null
        )

        val kotlinResult = pipeline.execute(kotlin, options(emptyMap()))
        val javaResult = pipeline.execute(java, options(emptyMap()))

        assertTrue(kotlinResult.isSuccess)
        assertTrue(javaResult.isSuccess)
        assertNull(kotlinResult.response)
        assertNull(javaResult.response)
    }

    @Test
    fun `generated descriptors preserve properties keys nullability and authorization`() {
        val handlers = ContractTestsArcArtifactModule().commandHandlers.associateBy { handler -> handler.commandType }
        val regular = requireNotNull(handlers[KotlinRegularCommand::class.java]).metadata
        val suspend = requireNotNull(handlers[KotlinSuspendCommand::class.java]).metadata
        val java = requireNotNull(handlers[JavaAsyncCommand::class.java]).metadata

        assertEquals("KotlinRegularCommand", regular.name)
        assertEquals(
            listOf("commandId", "message", "optionalLabel"),
            regular.properties.map { property -> property.name }
        )
        assertTrue(regular.properties.single { property -> property.name == "commandId" }.isCommandKey)
        assertTrue(regular.properties.single { property -> property.name == "optionalLabel" }.isNullable)
        assertTrue(regular.authorization.allowAnonymous)

        assertEquals(listOf("operator", "admin", "auditor"), suspend.authorization.roles)
        assertEquals(listOf("bearer"), suspend.authorization.schemes)
        assertEquals("orders", suspend.authorization.policy)
        assertTrue(suspend.treatWarningsAsErrors)
        assertFalse(suspend.authorization.allowAnonymous)

        assertEquals(listOf("commandId", "value"), java.properties.map { property -> property.name })
        assertTrue(java.properties.first().isCommandKey)
        assertEquals("java-policy", java.authorization.policy)
        assertEquals(listOf("java-operator", "java-admin"), java.authorization.roles)
        assertEquals(listOf("java-scheme"), java.authorization.schemes)

        assertEquals(
            "kotlin-key",
            requireNotNull(handlers[KotlinRegularCommand::class.java]).resolveCommandKey(
                KotlinRegularCommand("kotlin-key", "message", null)
            )
        )
        assertEquals(
            "java-key",
            requireNotNull(handlers[JavaAsyncCommand::class.java]).resolveCommandKey(
                JavaAsyncCommand("java-key", "value")
            )
        )
    }

    private fun createPipeline(
        responseValueHandlers: List<CommandResponseValueHandler> = emptyList()
    ): DefaultCommandPipeline = DefaultCommandPipeline(
        createCommandHandlers(),
        responseValueHandlers = responseValueHandlers
    )

    private fun createCommandHandlers(): ConcurrentCommandHandlerRegistry = ConcurrentCommandHandlerRegistry().also {
        commandHandlers ->
        ArcArtifactModuleRegistry.register(
            ContractTestsArcArtifactModule(),
            commandHandlers,
            ConcurrentQueryPerformerRegistry()
        )
    }

    private fun successfulAppend(sequenceNumber: Long): AppendResult = AppendResult(
        EventSequenceNumber(sequenceNumber),
        emptyList(),
        emptyList(),
        true
    )

    private fun recordingHandler(type: Class<*>, handledValues: MutableList<Any>): CommandResponseValueHandler =
        object : CommandResponseValueHandler {
            override fun canHandle(context: CommandContext, value: Any): Boolean = type.isInstance(value)

            override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
                handledValues.add(value)
                return CommandResult.success(context.correlationId)
            }
        }

    private fun options(
        services: Map<Class<*>, Any>,
        correlationId: UUID = UUID.randomUUID()
    ): CommandExecutionOptions = CommandExecutionOptions(
        correlationId,
        ArcPrincipal.anonymous(),
        MapServiceResolver(services)
    )

    private class MapServiceResolver(private val services: Map<Class<*>, Any>) : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = services[type]?.let(type::cast)
    }
}
