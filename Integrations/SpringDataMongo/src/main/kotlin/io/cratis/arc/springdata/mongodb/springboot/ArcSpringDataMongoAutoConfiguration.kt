// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb.springboot

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.springdata.mongodb.DefaultMongoCommandReadModelResolver
import io.cratis.arc.springdata.mongodb.MongoChangeStreamSource
import io.cratis.arc.springdata.mongodb.MongoChangeStreamWatcher
import io.cratis.arc.springdata.mongodb.MongoCommandExecutionScope
import io.cratis.arc.springdata.mongodb.MongoCommandReadModelResolver
import io.cratis.arc.springdata.mongodb.MongoObservableQuery
import io.cratis.arc.springdata.mongodb.MongoObservationOptions
import io.cratis.arc.springdata.mongodb.MongoOperationsResolver
import io.cratis.arc.springdata.mongodb.MongoReadModelForCommandResolver
import io.cratis.arc.springdata.mongodb.ReconnectingMongoChangeStreamWatcher
import io.cratis.arc.springdata.mongodb.SpringDataMongoChangeStreamSource
import io.cratis.arc.springdata.mongodb.TenantAwareMongoOperationsAdapter
import io.cratis.arc.springdata.mongodb.TenantAwareMongoOperationsResolver
import io.cratis.arc.springboot.ArcAutoConfiguration
import java.lang.reflect.Modifier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.mapping.MongoMappingContext

/** Auto-configures Arc's Spring Data MongoDB adapters and backs off for application-provided equivalents. */
@AutoConfiguration(
    after = [
        ArcAutoConfiguration::class,
        MongoAutoConfiguration::class,
        MongoDataAutoConfiguration::class,
        TransactionAutoConfiguration::class
    ]
)
@ConditionalOnClass(MongoOperations::class, MongoMappingContext::class)
public class ArcSpringDataMongoAutoConfiguration {
    /** Supplies bounded defaults for MongoDB change-stream observations. */
    @Bean
    @ConditionalOnMissingBean(MongoObservationOptions::class)
    public fun arcMongoObservationOptions(): MongoObservationOptions = MongoObservationOptions()

    /** Compatibility factory for the historical fixed MongoDB operations resolver. */
    public fun arcMongoOperationsResolver(mongoOperations: MongoOperations): MongoOperationsResolver =
        FixedMongoOperationsResolver(mongoOperations)

    /** Supplies a fixed resolver only when tenancy is optional and one MongoDB operations candidate exists. */
    @Bean("arcMongoOperationsResolver")
    @ConditionalOnSingleCandidate(MongoOperations::class)
    @ConditionalOnMissingBean(value = [MongoOperationsResolver::class, TenantAwareMongoOperationsResolver::class])
    @ConditionalOnProperty(
        prefix = "cratis.arc.tenancy",
        name = ["required"],
        havingValue = "false",
        matchIfMissing = true
    )
    internal fun arcFixedMongoOperationsResolver(mongoOperations: MongoOperations): FixedMongoOperationsResolver =
        FixedMongoOperationsResolver(mongoOperations)

    /** Adapts the single tenant-certified resolver to observation APIs that consume MongoOperationsResolver. */
    @Bean("arcTenantAwareMongoOperationsAdapter")
    @ConditionalOnSingleCandidate(TenantAwareMongoOperationsResolver::class)
    internal fun arcTenantAwareMongoOperationsAdapter(
        resolver: TenantAwareMongoOperationsResolver
    ): MongoOperationsResolver = TenantAwareMongoOperationsAdapter(resolver)

    /** Opens native Spring Data MongoDB change-stream cursors. */
    @Bean
    @ConditionalOnSingleCandidate(MongoOperationsResolver::class)
    @ConditionalOnMissingBean(MongoChangeStreamSource::class)
    public fun arcMongoChangeStreamSource(
        operationsResolver: MongoOperationsResolver,
        options: MongoObservationOptions
    ): MongoChangeStreamSource = SpringDataMongoChangeStreamSource(operationsResolver, options)

    /** Reconnects change streams and preserves finite backpressure for every collector. */
    @Bean
    @ConditionalOnBean(MongoChangeStreamSource::class)
    @ConditionalOnMissingBean(MongoChangeStreamWatcher::class)
    public fun arcMongoChangeStreamWatcher(
        source: MongoChangeStreamSource,
        options: MongoObservationOptions
    ): MongoChangeStreamWatcher = ReconnectingMongoChangeStreamWatcher(source, options)

    /** Provides injectable cold and shared Flow snapshot helpers for read-model queries. */
    @Bean(destroyMethod = "close")
    @ConditionalOnBean(MongoOperationsResolver::class, MongoChangeStreamWatcher::class)
    @ConditionalOnMissingBean(MongoObservableQuery::class)
    public fun arcMongoObservableQuery(
        operationsResolver: MongoOperationsResolver,
        watcher: MongoChangeStreamWatcher
    ): MongoObservableQuery = MongoObservableQuery(operationsResolver, watcher)

    /** Contributes MongoDB's ownership-aware command read-model provider independently of other store providers. */
    @Bean
    @ConditionalOnBean(MongoMappingContext::class)
    @ConditionalOnMissingBean(MongoReadModelForCommandResolver::class)
    public fun arcMongoReadModelForCommandResolver(
        mappingContext: MongoMappingContext,
        operationsResolvers: ObjectProvider<MongoOperationsResolver>,
        tenantOperationsResolvers: ObjectProvider<TenantAwareMongoOperationsResolver>,
        @Value("\${cratis.arc.tenancy.required:false}") tenancyRequired: Boolean
    ): MongoReadModelForCommandResolver {
        val tenantResolvers = tenantOperationsResolvers.orderedStream().toList()
        if (tenantResolvers.isNotEmpty()) {
            check(tenantResolvers.size == 1) {
                "MongoDB command read-model resolution needs at most one TenantAwareMongoOperationsResolver; " +
                    "found ${tenantResolvers.size}."
            }
            return MongoReadModelForCommandResolver(mappingContext, tenantResolvers.single(), tenancyRequired)
        }
        check(!tenancyRequired) {
            "Required MongoDB tenancy needs exactly one TenantAwareMongoOperationsResolver; found 0."
        }
        val fixedResolvers = operationsResolvers.orderedStream().toList()
            .filterNot { it is TenantAwareMongoOperationsAdapter }
        check(fixedResolvers.size == 1) {
            "MongoDB command read-model resolution needs exactly one fixed MongoOperationsResolver; " +
                "found ${fixedResolvers.size}."
        }
        return MongoReadModelForCommandResolver(mappingContext, fixedResolvers.single(), false)
    }

    /** Fails required-tenancy startup when mapped MongoDB read models do not have one tenant-aware resolver. */
    @Bean
    @ConditionalOnBean(MongoMappingContext::class)
    @ConditionalOnProperty(prefix = "cratis.arc.tenancy", name = ["required"], havingValue = "true")
    internal fun arcRequiredMongoTenancyGuard(
        mappingContext: MongoMappingContext,
        operationsResolvers: ObjectProvider<TenantAwareMongoOperationsResolver>,
        observationResolvers: ObjectProvider<MongoOperationsResolver>,
        readModelResolvers: ObjectProvider<MongoReadModelForCommandResolver>
    ): RequiredMongoTenancyGuard = RequiredMongoTenancyGuard(
        mappingContext,
        operationsResolvers,
        observationResolvers,
        readModelResolvers
    )

    /** Compatibility factory for explicitly constructing the historical fixed MongoDB command resolver. */
    public fun arcMongoCommandReadModelResolver(
        mongoOperations: MongoOperations,
        mappingContext: MongoMappingContext,
        handlers: CommandHandlerRegistry
    ): MongoCommandReadModelResolver = DefaultMongoCommandReadModelResolver(mongoOperations, mappingContext, handlers)

    /** Supplies the legacy API only for the auto-configured, verified fixed operations path. */
    @Bean("arcMongoCommandReadModelResolver")
    @ConditionalOnBean(FixedMongoOperationsResolver::class, MongoMappingContext::class, CommandHandlerRegistry::class)
    @ConditionalOnSingleCandidate(MongoOperations::class)
    @ConditionalOnMissingBean(
        value = [MongoCommandReadModelResolver::class, TenantAwareMongoOperationsResolver::class]
    )
    internal fun arcFixedMongoCommandReadModelResolver(
        operationsResolver: FixedMongoOperationsResolver,
        mappingContext: MongoMappingContext,
        handlers: CommandHandlerRegistry
    ): MongoCommandReadModelResolver = DefaultMongoCommandReadModelResolver(
        operationsResolver.operations,
        mappingContext,
        handlers
    )

    /**
     * Compatibility factory for explicitly constructing the historical MongoDB command transaction scope.
     *
     * This factory does not verify resource alignment or provide coroutine-safe transaction propagation.
     */
    public fun arcMongoCommandExecutionScope(
        transactionManager: MongoTransactionManager
    ): MongoCommandExecutionScope = MongoCommandExecutionScope(transactionManager)

    /** Adds an opted-in transaction scope only for aligned, auto-configured fixed operations. */
    @Bean("arcMongoCommandExecutionScope")
    @ConditionalOnBean(FixedMongoOperationsResolver::class)
    @ConditionalOnSingleCandidate(MongoTransactionManager::class)
    @ConditionalOnMissingBean(MongoCommandExecutionScope::class)
    @ConditionalOnProperty(
        prefix = "cratis.arc.spring-data.mongodb",
        name = ["command-transactions-enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    internal fun arcAlignedMongoCommandExecutionScope(
        operationsResolver: FixedMongoOperationsResolver,
        transactionManager: MongoTransactionManager
    ): MongoCommandExecutionScope {
        val operations = operationsResolver.resolve(null) as? MongoTemplate
            ?: error("MongoDB command transactions require the fixed operations bean to be a MongoTemplate.")
        check(operations.mongoDatabaseFactory === transactionManager.resourceFactory) {
            "MongoDB command transactions require MongoTemplate and MongoTransactionManager to share one resource factory."
        }
        return MongoCommandExecutionScope(transactionManager)
    }

    /** Validates explicit transaction opt-in even when scope bean conditions cannot match. */
    @Bean
    @ConditionalOnProperty(
        prefix = "cratis.arc.spring-data.mongodb",
        name = ["command-transactions-enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    internal fun arcMongoCommandTransactionGuard(
        fixedResolvers: ObjectProvider<FixedMongoOperationsResolver>,
        transactionManagers: ObjectProvider<MongoTransactionManager>
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val fixed = fixedResolvers.orderedStream().toList()
        val managers = transactionManagers.orderedStream().toList()
        check(fixed.size == 1) {
            "MongoDB command transactions require exactly one auto-configured fixed operations resolver; " +
                "found ${fixed.size}."
        }
        check(managers.size == 1) {
            "MongoDB command transactions require exactly one MongoTransactionManager; found ${managers.size}."
        }
        val operations = fixed.single().resolve(null) as? MongoTemplate
            ?: error("MongoDB command transactions require the fixed operations bean to be a MongoTemplate.")
        check(operations.mongoDatabaseFactory === managers.single().resourceFactory) {
            "MongoDB command transactions require MongoTemplate and MongoTransactionManager to share one resource factory."
        }
    }
}

internal class FixedMongoOperationsResolver(internal val operations: MongoOperations) : MongoOperationsResolver {
    override fun resolve(tenantId: String?): MongoOperations {
        require(tenantId == null) { "The fixed MongoOperationsResolver cannot resolve tenant '$tenantId'." }
        return operations
    }
}

internal class RequiredMongoTenancyGuard(
    private val mappingContext: MongoMappingContext,
    private val operationsResolvers: ObjectProvider<TenantAwareMongoOperationsResolver>,
    private val observationResolvers: ObjectProvider<MongoOperationsResolver>,
    private val readModelResolvers: ObjectProvider<MongoReadModelForCommandResolver>
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val hasMappedReadModels = mappingContext.persistentEntities.any { entity ->
            val type = entity.type
            type.isAnnotationPresent(ReadModel::class.java) &&
                !type.isInterface &&
                !Modifier.isAbstract(type.modifiers)
        }
        if (!hasMappedReadModels) return

        val tenantAwareResolvers = operationsResolvers.orderedStream().toList()
        check(tenantAwareResolvers.size == 1) {
            "Required MongoDB tenancy with mapped @ReadModel documents needs exactly one " +
                "TenantAwareMongoOperationsResolver; found ${tenantAwareResolvers.size}."
        }

        val observation = observationResolvers.orderedStream().toList()
        val certifiedAdapters = observation.filterIsInstance<TenantAwareMongoOperationsAdapter>()
        check(observation.size == 1 && certifiedAdapters.size == 1 &&
            certifiedAdapters.single().resolver === tenantAwareResolvers.single()) {
            "Required MongoDB tenancy needs observation and change-stream resolution to use only the adapter for " +
                "the exact TenantAwareMongoOperationsResolver bean."
        }

        val providers = readModelResolvers.orderedStream().toList()
        check(providers.size == 1) {
            "Required MongoDB tenancy with mapped @ReadModel documents needs exactly one " +
                "MongoReadModelForCommandResolver; found ${providers.size}."
        }
        val provider = providers.single()
        val mappedTypes = mappingContext.persistentEntities.asSequence()
            .map { it.type }
            .filter { it.isAnnotationPresent(ReadModel::class.java) && !it.isInterface && !Modifier.isAbstract(it.modifiers) }
            .mapTo(sortedSetOf()) { it.name }
        val providerTypes = provider.readModelTypes().mapTo(sortedSetOf()) { it.name }
        check(providerTypes == mappedTypes) {
            "Required MongoDB tenancy needs MongoReadModelForCommandResolver to claim every mapped @ReadModel " +
                "document exactly; mapped=$mappedTypes, claimed=$providerTypes."
        }
        check(provider.tenancyRequired) {
            "Required MongoDB tenancy needs MongoReadModelForCommandResolver configured with tenancyRequired=true."
        }
        check(provider.tenantOperationsResolver === tenantAwareResolvers.single()) {
            "Required MongoDB tenancy needs MongoReadModelForCommandResolver configured with the exact " +
                "TenantAwareMongoOperationsResolver bean."
        }
    }
}
