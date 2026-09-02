// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.results.ValidationResultSeverity
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/** Immutable context carried explicitly through every command execution stage. */
public class CommandContext @JvmOverloads constructor(
    /** Correlation identifier for the execution. */
    public val correlationId: UUID,
    /** Command instance being executed. */
    public val command: Any,
    /** Runtime command type used for generated-handler lookup. */
    public val commandType: Class<*>,
    /** Identity on whose behalf the command runs. */
    public val principal: ArcPrincipal,
    /** Host tenant identifier, when available. */
    public val tenantId: String? = null,
    /** Host tenant namespace, when available. */
    public val tenantNamespace: String? = null,
    /** Maximum validation severity allowed without rejecting execution. */
    public val allowedValidationSeverity: ValidationResultSeverity? = null,
    /** Resolver scoped to this command execution. */
    public val serviceResolver: ServiceResolver,
    /** Whether a host may expose retained exception details. */
    public val exposeExceptionDetails: Boolean = false,
    /** Client response identified while processing the handler return value. */
    public val response: Any? = null,
    /** Immutable named values contributed before scopes and filters run. */
    values: Map<String, Any> = emptyMap(),
    /** Immutable ordered values produced by the command's `provide` phase. */
    providedValues: Collection<Any> = emptyList(),
    /** Key resolved by the generated command handler, used for command-side read-model lookup. */
    public val commandKey: Any? = (command as? CommandKeyProvider)?.commandKey()
) {
    private var executionTokenValue: CommandExecutionToken? = null

    /** Opaque identity of this pipeline execution frame, or `null` for a manually created context. */
    public val executionToken: CommandExecutionToken?
        get() = executionTokenValue

    public val values: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(values))
    public val providedValues: List<Any> = java.util.List.copyOf(providedValues)
    init {
        require(commandType.isInstance(command)) {
            "Command of type '${command.javaClass.name}' is not an instance of '${commandType.name}'."
        }
    }

    internal fun attachExecutionToken(token: CommandExecutionToken): CommandContext = apply {
        check(executionTokenValue == null || executionTokenValue === token) {
            "A command context cannot change execution ownership."
        }
        executionTokenValue = token
    }

    internal fun withResponse(value: Any?): CommandContext = CommandContext(
        correlationId,
        command,
        commandType,
        principal,
        tenantId,
        tenantNamespace,
        allowedValidationSeverity,
        serviceResolver,
        exposeExceptionDetails,
        value,
        values,
        providedValues,
        commandKey
    ).also { copy -> executionTokenValue?.let(copy::attachExecutionToken) }

    internal fun withProvidedValues(values: Collection<Any>): CommandContext = CommandContext(
        correlationId,
        command,
        commandType,
        principal,
        tenantId,
        tenantNamespace,
        allowedValidationSeverity,
        serviceResolver,
        exposeExceptionDetails,
        response,
        this.values,
        values,
        commandKey
    ).also { copy -> executionTokenValue?.let(copy::attachExecutionToken) }
}
