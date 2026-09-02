// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle.springboot

import io.cratis.arc.chronicle.ChronicleCommandExecutionScope
import io.cratis.arc.chronicle.ChronicleCommandResponseValueHandler
import io.cratis.arc.chronicle.ChronicleCommandSideEffectHandler
import io.cratis.arc.chronicle.ChronicleCommandTransaction
import io.cratis.arc.chronicle.ChronicleReadModelForCommandResolver
import io.cratis.arc.chronicle.ChronicleReadModelInterceptor
import io.cratis.arc.chronicle.EventsWithConcurrencyScopesCommandResponseValueHandler
import io.cratis.arc.chronicle.TenantEventStoreProvider
import io.cratis.arc.chronicle.TenantEventStoreResolver
import io.cratis.arc.chronicle.tenantEventStoreResolver as composeTenantEventStoreResolver
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.springboot.ArcApplicationCoroutineScope
import io.cratis.chronicle.ChronicleOptions
import io.cratis.chronicle.IEventStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean

/** Adds Chronicle event responses to Arc when both runtimes are available. */
@AutoConfiguration(
    afterName = [
        "io.cratis.arc.springboot.ArcAutoConfiguration",
        "io.cratis.chronicle.spring.ChronicleAutoConfiguration"
    ]
)
@ConditionalOnClass(IEventStore::class, CommandResponseValueHandler::class)
public class ChronicleArcAutoConfiguration {
    /** Resolves tenant stores from an optional provider and the default Chronicle store. */
    @Bean
    @ConditionalOnBean(IEventStore::class)
    @ConditionalOnMissingBean(TenantEventStoreResolver::class)
    public fun tenantEventStoreResolver(
        eventStore: IEventStore,
        provider: ObjectProvider<TenantEventStoreProvider>
    ): TenantEventStoreResolver = composeTenantEventStoreResolver(eventStore, provider.getIfAvailable())

    /** Holds explicit per-command Chronicle units of work without thread-local state. */
    @Bean
    @ConditionalOnBean(TenantEventStoreResolver::class)
    @ConditionalOnMissingBean(ChronicleCommandTransaction::class)
    public fun chronicleCommandTransaction(): ChronicleCommandTransaction = ChronicleCommandTransaction()

    /** Commits successful command units of work and rolls failed commands back. */
    @Bean
    @ConditionalOnBean(ChronicleCommandTransaction::class)
    @ConditionalOnMissingBean(ChronicleCommandExecutionScope::class)
    public fun chronicleCommandExecutionScope(
        transactions: ChronicleCommandTransaction
    ): ChronicleCommandExecutionScope = ChronicleCommandExecutionScope(transactions)

    /** Appends event values returned by commands and backs off for application-provided behavior. */
    @Bean
    @ConditionalOnBean(TenantEventStoreResolver::class, CommandHandlerRegistry::class)
    @ConditionalOnMissingBean(ChronicleCommandResponseValueHandler::class)
    public fun chronicleCommandResponseValueHandler(
        eventStoreResolver: TenantEventStoreResolver,
        commandHandlers: CommandHandlerRegistry,
        transactions: ChronicleCommandTransaction
    ): ChronicleCommandResponseValueHandler =
        ChronicleCommandResponseValueHandler(eventStoreResolver, commandHandlers, transactions)

    /** Handles cross-source event batches carrying exact concurrency scopes. */
    @Bean
    @ConditionalOnBean(TenantEventStoreResolver::class, ChronicleCommandTransaction::class)
    @ConditionalOnMissingBean(EventsWithConcurrencyScopesCommandResponseValueHandler::class)
    public fun eventsWithConcurrencyScopesCommandResponseValueHandler(
        eventStoreResolver: TenantEventStoreResolver,
        transactions: ChronicleCommandTransaction
    ): EventsWithConcurrencyScopesCommandResponseValueHandler =
        EventsWithConcurrencyScopesCommandResponseValueHandler(eventStoreResolver, transactions)

    /** Resolves Chronicle-discovered read models for command parameters with declared ownership. */
    @Bean
    @ConditionalOnBean(ChronicleOptions::class, TenantEventStoreResolver::class, ArcApplicationCoroutineScope::class)
    @ConditionalOnMissingBean(ChronicleReadModelForCommandResolver::class)
    public fun chronicleReadModelForCommandResolver(
        options: ChronicleOptions,
        eventStoreResolver: TenantEventStoreResolver,
        coroutineScope: ArcApplicationCoroutineScope
    ): ChronicleReadModelForCommandResolver = ChronicleReadModelForCommandResolver(
        options.artifacts.readModels.map { it.java },
        eventStoreResolver,
        coroutineScope
    )

    /** Releases compliance-protected values for Chronicle-discovered query read models. */
    @Bean
    @ConditionalOnBean(ChronicleOptions::class, TenantEventStoreResolver::class, ArcApplicationCoroutineScope::class)
    @ConditionalOnMissingBean(ChronicleReadModelInterceptor::class)
    public fun chronicleReadModelInterceptor(
        options: ChronicleOptions,
        eventStoreResolver: TenantEventStoreResolver,
        coroutineScope: ArcApplicationCoroutineScope
    ): ChronicleReadModelInterceptor = ChronicleReadModelInterceptor(
        options.artifacts.readModels.map { it.java },
        eventStoreResolver,
        coroutineScope
    )

    /** Executes explicitly handed-off reactor command side effects without ambient privilege. */
    @Bean
    @ConditionalOnBean(
        CommandPipeline::class,
        CommandHandlerRegistry::class,
        ServiceResolver::class,
        ArcApplicationCoroutineScope::class
    )
    @ConditionalOnMissingBean(ChronicleCommandSideEffectHandler::class)
    public fun chronicleCommandSideEffectHandler(
        pipeline: CommandPipeline,
        commandHandlers: CommandHandlerRegistry,
        serviceResolver: ServiceResolver,
        coroutineScope: ArcApplicationCoroutineScope
    ): ChronicleCommandSideEffectHandler = ChronicleCommandSideEffectHandler(
        pipeline,
        commandHandlers,
        serviceResolver,
        coroutineScope
    )
}
