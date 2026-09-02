// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa.springboot

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.springdata.jpa.DatabaseChangeNotifier
import io.cratis.arc.springdata.jpa.DefaultJpaCommandReadModelResolver
import io.cratis.arc.springdata.jpa.FixedJpaPersistenceUnitResolver
import io.cratis.arc.springdata.jpa.JpaCommandExecutionScope
import io.cratis.arc.springdata.jpa.JpaCommandReadModelResolver
import io.cratis.arc.springdata.jpa.JpaObservableQuery
import io.cratis.arc.springdata.jpa.JpaObservationOptions
import io.cratis.arc.springdata.jpa.JpaPersistenceUnit
import io.cratis.arc.springdata.jpa.JpaPersistenceUnitResolver
import io.cratis.arc.springdata.jpa.JpaReadModelForCommandResolver
import io.cratis.arc.springdata.jpa.TransactionAwareDatabaseChangeNotifier
import io.cratis.arc.springboot.ArcAutoConfiguration
import io.cratis.arc.springboot.ArcProperties
import jakarta.persistence.EntityManagerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.PlatformTransactionManager

/** Auto-configures Arc's Spring Data JPA adapters and backs off for application-provided equivalents. */
@AutoConfiguration(
    after = [ArcAutoConfiguration::class, HibernateJpaAutoConfiguration::class, TransactionAutoConfiguration::class]
)
@ConditionalOnClass(EntityManagerFactory::class, PlatformTransactionManager::class)
public class ArcSpringDataJpaAutoConfiguration {
    /** Supplies finite buffering and coalescing defaults for JPA observations. */
    @Bean
    @ConditionalOnMissingBean(JpaObservationOptions::class)
    public fun arcJpaObservationOptions(): JpaObservationOptions = JpaObservationOptions()

    /** Publishes local changes after commit and backs off for a database-native notifier. */
    @Bean
    @ConditionalOnMissingBean(DatabaseChangeNotifier::class)
    public fun arcDatabaseChangeNotifier(
        options: JpaObservationOptions
    ): TransactionAwareDatabaseChangeNotifier = TransactionAwareDatabaseChangeNotifier(options)

    /** Provides injectable cold and shared Flow snapshot helpers for read-model queries. */
    @Bean(destroyMethod = "close")
    @ConditionalOnSingleCandidate(EntityManagerFactory::class)
    @ConditionalOnMissingBean(JpaObservableQuery::class)
    public fun arcJpaObservableQuery(
        entityManagerFactory: EntityManagerFactory,
        notifier: DatabaseChangeNotifier,
        options: JpaObservationOptions
    ): JpaObservableQuery = JpaObservableQuery(entityManagerFactory, notifier, options)

    /** Compatibility factory for constructing one fixed, non-tenant persistence-unit resolver. */
    public fun arcJpaPersistenceUnitResolver(
        entityManagerFactory: EntityManagerFactory,
        transactionManagers: ObjectProvider<PlatformTransactionManager>
    ): JpaPersistenceUnitResolver = fixedPersistenceUnitResolver(entityManagerFactory, transactionManagers)

    /** Supplies a verifiably fixed persistence-unit path only when tenant isolation is not required. */
    @Bean("arcJpaPersistenceUnitResolver")
    @ConditionalOnProperty(
        prefix = "cratis.arc.tenancy",
        name = ["required"],
        havingValue = "false",
        matchIfMissing = true
    )
    @ConditionalOnSingleCandidate(EntityManagerFactory::class)
    @ConditionalOnMissingBean(JpaPersistenceUnitResolver::class)
    internal fun arcFixedJpaPersistenceUnitResolver(
        entityManagerFactory: EntityManagerFactory,
        transactionManagers: ObjectProvider<PlatformTransactionManager>
    ): FixedJpaPersistenceUnitResolver = fixedPersistenceUnitResolver(entityManagerFactory, transactionManagers)

    /** Exposes the ownership-aware contextual JPA provider without considering unrelated storage providers. */
    @Bean
    @ConditionalOnSingleCandidate(JpaPersistenceUnitResolver::class)
    @ConditionalOnMissingBean(JpaReadModelForCommandResolver::class)
    public fun arcJpaReadModelForCommandResolver(
        persistenceUnits: JpaPersistenceUnitResolver,
        properties: ArcProperties
    ): JpaReadModelForCommandResolver = JpaReadModelForCommandResolver(
        persistenceUnits,
        properties.tenancy.isRequired
    )

    /** Validates the complete contextual provider chain when JPA read models require tenant isolation. */
    @Bean
    @ConditionalOnProperty(prefix = "cratis.arc.tenancy", name = ["required"], havingValue = "true")
    public fun arcJpaRequiredTenancyPersistenceUnitGuard(
        entityManagerFactories: ObjectProvider<EntityManagerFactory>,
        persistenceUnits: ObjectProvider<JpaPersistenceUnitResolver>,
        readModelResolvers: ObjectProvider<JpaReadModelForCommandResolver>
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val mappedReadModels = linkedSetOf<String>()
        entityManagerFactories.forEach { factory ->
            factory.metamodel.entities
                .map { entity -> entity.javaType }
                .filter { type -> type.isAnnotationPresent(ReadModel::class.java) }
                .mapTo(mappedReadModels) { type -> type.name }
        }
        val unitResolvers = persistenceUnits.orderedStream().toList()
        val providers = readModelResolvers.orderedStream().toList()
        val claimedTypes = (unitResolvers.flatMap { it.readModelTypes() } + providers.flatMap { it.readModelTypes() })
            .mapTo(linkedSetOf()) { it.name }
        if (mappedReadModels.isEmpty() && claimedTypes.isEmpty()) return@SmartInitializingSingleton

        check(unitResolvers.size == 1) {
            "Required JPA tenancy with mapped @ReadModel entities needs exactly one " +
                "JpaPersistenceUnitResolver; found ${unitResolvers.size}."
        }
        check(providers.size == 1) {
            "Required JPA tenancy with mapped @ReadModel entities needs exactly one " +
                "JpaReadModelForCommandResolver; found ${providers.size}."
        }
        val unitResolver = unitResolvers.single()
        val provider = providers.single()
        val unitTypes = unitResolver.readModelTypes().mapTo(sortedSetOf()) { it.name }
        val providerTypes = provider.readModelTypes().mapTo(sortedSetOf()) { it.name }
        check(mappedReadModels.all(unitTypes::contains)) {
            "Required JPA tenancy needs JpaPersistenceUnitResolver to claim every mapped @ReadModel type; " +
                "mapped=${mappedReadModels.sorted()}, claimed=$unitTypes."
        }
        check(providerTypes == unitTypes) {
            "Required JPA tenancy needs JpaReadModelForCommandResolver claims to exactly match its " +
                "JpaPersistenceUnitResolver; provider=$providerTypes, resolver=$unitTypes."
        }
        check(provider.tenancyRequired) {
            "Required JPA tenancy needs JpaReadModelForCommandResolver configured with tenancyRequired=true."
        }
        check(provider.persistenceUnits === unitResolver) {
            "Required JPA tenancy needs JpaReadModelForCommandResolver configured with the exact " +
                "JpaPersistenceUnitResolver bean."
        }
    }

    /** Compatibility factory for explicitly constructing the historical fixed JPA command resolver. */
    public fun arcJpaCommandReadModelResolver(
        entityManagerFactory: EntityManagerFactory,
        handlers: CommandHandlerRegistry
    ): JpaCommandReadModelResolver = DefaultJpaCommandReadModelResolver(entityManagerFactory, handlers)

    /** Supplies the legacy API only for the auto-configured, verified fixed persistence-unit path. */
    @Bean("arcJpaCommandReadModelResolver")
    @ConditionalOnBean(FixedJpaPersistenceUnitResolver::class, CommandHandlerRegistry::class)
    @ConditionalOnMissingBean(JpaCommandReadModelResolver::class)
    internal fun arcFixedJpaCommandReadModelResolver(
        persistenceUnitResolver: FixedJpaPersistenceUnitResolver,
        handlers: CommandHandlerRegistry
    ): JpaCommandReadModelResolver = DefaultJpaCommandReadModelResolver(
        persistenceUnitResolver.persistenceUnit.entityManagerFactory,
        handlers
    )

    /** Adds an explicitly opted-in scope only for the fixed unit's aligned JPA transaction manager. */
    @Bean("arcJpaCommandExecutionScope")
    @ConditionalOnBean(FixedJpaPersistenceUnitResolver::class)
    @ConditionalOnMissingBean(JpaCommandExecutionScope::class)
    @ConditionalOnProperty(
        prefix = "cratis.arc.spring-data.jpa",
        name = ["command-transactions-enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    internal fun arcJpaAlignedCommandExecutionScope(
        persistenceUnitResolver: FixedJpaPersistenceUnitResolver
    ): JpaCommandExecutionScope {
        val transactionManager = checkNotNull(persistenceUnitResolver.persistenceUnit.transactionManager) {
            "JPA command transactions were enabled, but the fixed persistence unit has no aligned " +
                "JpaTransactionManager."
        }
        return JpaCommandExecutionScope(transactionManager)
    }

    /** Validates explicit transaction opt-in before any imperative scope is used. */
    @Bean
    @ConditionalOnProperty(
        prefix = "cratis.arc.spring-data.jpa",
        name = ["command-transactions-enabled"],
        havingValue = "true",
        matchIfMissing = false
    )
    internal fun arcJpaCommandTransactionGuard(
        fixedResolvers: ObjectProvider<FixedJpaPersistenceUnitResolver>
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val fixed = fixedResolvers.orderedStream().toList()
        check(fixed.size == 1) {
            "JPA command transactions require exactly one auto-configured fixed persistence unit; found ${fixed.size}."
        }
        checkNotNull(fixed.single().persistenceUnit.transactionManager) {
            "JPA command transactions require the fixed persistence unit to have an aligned JpaTransactionManager."
        }
    }

    /**
     * Compatibility factory retained for callers compiled against the original API.
     *
     * This is intentionally not an auto-configured bean because an arbitrary transaction manager is not evidence that
     * it is aligned with the JPA persistence unit used for command read models.
     */
    public fun arcJpaCommandExecutionScope(
        transactionManager: PlatformTransactionManager
    ): JpaCommandExecutionScope = JpaCommandExecutionScope(transactionManager)
    private fun fixedPersistenceUnitResolver(
        entityManagerFactory: EntityManagerFactory,
        transactionManagers: ObjectProvider<PlatformTransactionManager>
    ): FixedJpaPersistenceUnitResolver {
        val transactionManager = transactionManagers.orderedStream().toList()
            .filterIsInstance<JpaTransactionManager>()
            .singleOrNull()
            ?.takeIf { manager -> manager.entityManagerFactory === entityManagerFactory }
        val persistenceUnit = if (transactionManager == null) {
            JpaPersistenceUnit.fixed(entityManagerFactory)
        } else {
            JpaPersistenceUnit.fixed(entityManagerFactory, transactionManager)
        }
        return FixedJpaPersistenceUnitResolver(persistenceUnit)
    }
}
