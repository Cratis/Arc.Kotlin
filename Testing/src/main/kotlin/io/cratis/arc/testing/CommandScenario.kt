// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationEvaluator
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry
import io.cratis.arc.commands.CommandAuthorizationFilter
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandContextValuesProvider
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.queries.CanResolveReadModelForCommand
import io.cratis.arc.queries.ReadModelForCommandResolverRegistry
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.tenancy.TenantIdResolver
import io.cratis.arc.tenancy.TenantResolutionContext
import java.util.LinkedHashMap
import java.util.ServiceLoader
import java.util.UUID

/** Runs one exact generated or manual command handler through the real in-process Arc command pipeline. */
public class CommandScenario<TCommand : Any> private constructor(
    private val artifacts: ScenarioArtifactRegistry,
    private val commandType: Class<TCommand>
) {
    private val selectedHandler: CommandHandler = artifacts.command(commandType)
    private val filters = mutableListOf<CommandFilter>()
    private val scopes = mutableListOf<CommandExecutionScope>()
    private val responseHandlers = mutableListOf<CommandResponseValueHandler>()
    private val contextValuesProviders = mutableListOf<CommandContextValuesProvider>()
    private val validators = mutableListOf<CommandValidator<*>>()
    private val readModelResolvers = mutableListOf<CanResolveReadModelForCommand>()
    private val policies = LinkedHashMap<String, AuthorizationPolicy>()
    private val extensions = LinkedHashMap<Class<out CommandScenarioExtender>, CommandScenarioExtender>()
    private val objectMapper: ObjectMapper = ArcObjectMapper.create()
    private var services: ScenarioServiceResolver = ScenarioServiceResolver.empty()
    private var principal: ArcPrincipal = ArcPrincipal.anonymous()
    private var tenantId: String? = null
    private var tenantNamespace: String? = null
    private var correlationId: UUID = UUID.randomUUID()
    private var allowedValidationSeverity: ValidationResultSeverity? =
        if (selectedHandler.metadata.treatWarningsAsErrors) ValidationResultSeverity.Information else null
    private var exposeExceptionDetails: Boolean = true
    private var serializationRoundTrip: Boolean = true

    init {
        ServiceLoader.load(CommandScenarioExtender::class.java)
            .toList()
            .sortedBy { it.javaClass.name }
            .forEach(::addExtension)
    }

    /** Creates a scenario from a complete generated [module] and exact [commandType]. */
    public constructor(module: ArcArtifactModule, commandType: Class<TCommand>) :
        this(ScenarioArtifactRegistry().register(module), commandType)

    /** Creates a scenario around one real manual [handler]. No placeholder handler is synthesized. */
    @Suppress("UNCHECKED_CAST")
    public constructor(handler: CommandHandler) :
        this(ScenarioArtifactRegistry().register(handler), handler.commandType as Class<TCommand>)

    /** Adds an integration-specific scenario policy and returns this scenario. */
    public fun addExtension(extender: CommandScenarioExtender): CommandScenario<TCommand> = apply {
        @Suppress("UNCHECKED_CAST")
        val type = extender.javaClass as Class<out CommandScenarioExtender>
        require(!extensions.containsKey(type)) { "Command scenario extension '${type.name}' is already configured." }
        extender.extend(
            CommandScenarioExtensionContext(
                artifacts.commandHandlers,
                scopes::add,
                responseHandlers::add,
                readModelResolvers::add,
                { type, service -> services = services.putUntyped(type, service) },
                ::addPolicyFromExtension
            )
        )
        extensions[type] = extender
    }

    /** Gets an applied integration extension by its exact implementation type. */
    public fun <TExtender : CommandScenarioExtender> extension(type: Class<TExtender>): TExtender =
        type.cast(extensions[type] ?: throw ScenarioSetupException("Command scenario extension '${type.name}' is not configured."))

    /** Kotlin convenience for [extension]. */
    public inline fun <reified TExtender : CommandScenarioExtender> extension(): TExtender =
        extension(TExtender::class.java)

    /** Adds a command filter and returns this scenario. */
    public fun addFilter(filter: CommandFilter): CommandScenario<TCommand> = apply { filters.add(filter) }

    /** Adds an execution scope and returns this scenario. */
    public fun addScope(scope: CommandExecutionScope): CommandScenario<TCommand> = apply { scopes.add(scope) }

    /** Adds a response value handler and returns this scenario. */
    public fun addResponseHandler(handler: CommandResponseValueHandler): CommandScenario<TCommand> =
        apply { responseHandlers.add(handler) }

    /** Adds a command context values provider and returns this scenario. */
    public fun addContextValuesProvider(provider: CommandContextValuesProvider): CommandScenario<TCommand> =
        apply { contextValuesProviders.add(provider) }

    /** Adds a typed command validator and returns this scenario. */
    public fun addValidator(validator: CommandValidator<*>): CommandScenario<TCommand> = apply { validators.add(validator) }

    /** Adds a command-side read-model resolver and returns this scenario. */
    public fun addReadModelResolver(resolver: CanResolveReadModelForCommand): CommandScenario<TCommand> = apply {
        readModelResolvers.add(resolver)
    }

    /** Adds a named authorization policy, rejecting duplicate names. */
    public fun addPolicy(name: String, policy: AuthorizationPolicy): CommandScenario<TCommand> = apply {
        require(name.isNotBlank()) { "Policy name cannot be blank." }
        require(!policies.containsKey(name)) { "An authorization policy named '$name' is already configured." }
        policies[name] = policy
    }

    /** Replaces the immutable service resolver and returns this scenario. */
    public fun withServices(services: ScenarioServiceResolver): CommandScenario<TCommand> = apply {
        this.services = services
    }

    /** Adds one exact service registration by replacing the immutable resolver snapshot. */
    public fun <T : Any> addService(type: Class<T>, service: T): CommandScenario<TCommand> = apply {
        services = services.put(type, service)
    }

    /** Kotlin convenience for [addService]. */
    public inline fun <reified T : Any> addService(service: T): CommandScenario<TCommand> =
        addService(T::class.java, service)

    /** Uses [principal] for authorization. */
    public fun withPrincipal(principal: ArcPrincipal): CommandScenario<TCommand> = apply { this.principal = principal }

    /** Uses explicit tenant context. */
    @JvmOverloads
    public fun withTenant(tenantId: String?, tenantNamespace: String? = tenantId): CommandScenario<TCommand> = apply {
        this.tenantId = tenantId
        this.tenantNamespace = tenantNamespace
    }

    /** Resolves tenant context explicitly and uses the resolved identifier as the default namespace. */
    @JvmOverloads
    public fun withTenantResolution(
        resolver: TenantIdResolver,
        context: TenantResolutionContext,
        tenantNamespace: String? = null
    ): CommandScenario<TCommand> = apply {
        val resolved = resolver.resolve(context)?.value()
        tenantId = resolved
        this.tenantNamespace = tenantNamespace ?: resolved
    }

    /** Uses a stable correlation identifier. */
    public fun withCorrelationId(correlationId: UUID): CommandScenario<TCommand> = apply {
        this.correlationId = correlationId
    }

    /** Configures the maximum non-blocking validation severity. */
    public fun withAllowedValidationSeverity(
        severity: ValidationResultSeverity?
    ): CommandScenario<TCommand> = apply { allowedValidationSeverity = severity }

    /** Configures retention of exception details in the real pipeline result. */
    public fun withExposedExceptionDetails(enabled: Boolean): CommandScenario<TCommand> = apply {
        exposeExceptionDetails = enabled
    }

    /** Enables or disables the default Arc JSON round trip before pipeline execution. */
    public fun withSerializationRoundTrip(enabled: Boolean): CommandScenario<TCommand> = apply {
        serializationRoundTrip = enabled
    }

    /** Executes [command] through authorization, validation, custom filters, scopes, and response handling. */
    public suspend fun execute(command: TCommand): CommandScenarioResult<Any?> {
        val prepared = prepare(command)
        return wrap(pipeline().execute(prepared, options()))
    }

    /** Validates [command] through the same filters without invoking the handler or scopes. */
    public suspend fun validate(command: TCommand): CommandScenarioResult<Any?> {
        val prepared = prepare(command)
        return wrap(pipeline().validate(prepared, options()))
    }

    private fun prepare(command: TCommand): TCommand {
        require(commandType.isInstance(command)) {
            "Expected command '${commandType.name}', but received '${command.javaClass.name}'."
        }
        if (!serializationRoundTrip) return command
        val bytes = objectMapper.writeValueAsBytes(command)
        return objectMapper.readValue(bytes, commandType)
            ?: throw ScenarioSetupException("Arc JSON deserialized '${commandType.name}' to null.")
    }

    private fun addPolicyFromExtension(name: String, policy: AuthorizationPolicy) {
        require(name.isNotBlank()) { "Policy name cannot be blank." }
        require(!policies.containsKey(name)) { "An authorization policy named '$name' is already configured." }
        policies[name] = policy
    }

    private fun pipeline(): DefaultCommandPipeline {
        val policyRegistry = ConcurrentAuthorizationPolicyRegistry()
        policies.forEach(policyRegistry::register)
        val builtInFilters = listOf<CommandFilter>(
            CommandAuthorizationFilter(artifacts.commandHandlers, AuthorizationEvaluator(policyRegistry)),
            DefaultCommandValidationFilter(validators)
        )
        return DefaultCommandPipeline(
            artifacts.commandHandlers,
            builtInFilters + filters,
            scopes,
            responseHandlers,
            contextValuesProviders
        )
    }

    private fun options(): CommandExecutionOptions = CommandExecutionOptions(
        correlationId,
        principal,
        servicesWithReadModelResolvers(),
        tenantId,
        tenantNamespace,
        allowedValidationSeverity,
        exposeExceptionDetails
    )

    private fun servicesWithReadModelResolvers(): ScenarioServiceResolver {
        if (readModelResolvers.isEmpty()) return services
        require(!services.contains(ReadModelForCommandResolverRegistry::class.java)) {
            "Configure command-side read-model resolvers either with addReadModelResolver or as an explicit registry service, not both."
        }
        return services.put(
            ReadModelForCommandResolverRegistry::class.java,
            ReadModelForCommandResolverRegistry(readModelResolvers)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrap(result: CommandResult<*>): CommandScenarioResult<Any?> =
        CommandScenarioResult(result as CommandResult<Any?>)
}
