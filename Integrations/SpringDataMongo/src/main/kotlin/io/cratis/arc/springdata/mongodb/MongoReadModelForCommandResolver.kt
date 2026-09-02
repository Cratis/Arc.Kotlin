// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.queries.BlockingReadModelForCommandResolver
import io.cratis.arc.queries.ReadModelForCommandOwnership
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.util.ClassUtils

/** Resolves mapped MongoDB read models from the command context's already-generated key. */
public class MongoReadModelForCommandResolver private constructor(
    mappingContext: MongoMappingContext,
    internal val fixedOperationsResolver: MongoOperationsResolver?,
    internal val tenantOperationsResolver: TenantAwareMongoOperationsResolver?,
    internal val tenancyRequired: Boolean
) : BlockingReadModelForCommandResolver {
    /** Creates a resolver for a fixed, non-tenant MongoDB store. */
    @JvmOverloads
    public constructor(
        mappingContext: MongoMappingContext,
        operationsResolver: MongoOperationsResolver,
        tenancyRequired: Boolean = false
    ) : this(mappingContext, operationsResolver, null, tenancyRequired) {
        require(!tenancyRequired) {
            "Required MongoDB tenancy needs a TenantAwareMongoOperationsResolver."
        }
    }

    /** Creates a resolver for tenant-certified MongoDB stores. */
    @JvmOverloads
    public constructor(
        mappingContext: MongoMappingContext,
        operationsResolver: TenantAwareMongoOperationsResolver,
        tenancyRequired: Boolean = true
    ) : this(mappingContext, null, operationsResolver, tenancyRequired)

    private val entitiesByType: Map<Class<*>, EntityClaim> = snapshotClaims(mappingContext)
    private val readModelTypes: Set<Class<*>> = Collections.unmodifiableSet(
        LinkedHashSet(entitiesByType.keys)
    )

    override fun readModelTypes(): Set<Class<*>> = readModelTypes

    override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.FALLBACK

    override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? {
        val claim = entitiesByType[readModelType] ?: return null
        require(claim.idType.isInstance(key)) {
            "Command key type '${key.javaClass.name}' is not assignable to MongoDB identifier type " +
                "'${claim.idType.name}' for '${readModelType.name}'."
        }

        val tenantId = commandContext.tenantId
        val operations = if (tenantId == null) {
            require(!tenancyRequired) {
                "A nonblank tenant identifier is required to resolve MongoDB read model '${readModelType.name}'."
            }
            val fixedResolver = requireNotNull(fixedOperationsResolver) {
                "A tenant identifier is required by the configured tenant-aware MongoDB resolver."
            }
            fixedResolver.resolve(null)
        } else {
            require(tenantId.isNotBlank()) {
                "A nonblank tenant identifier is required to resolve MongoDB read model '${readModelType.name}'."
            }
            val resolver = requireNotNull(tenantOperationsResolver) {
                "Tenant '$tenantId' cannot be resolved by a fixed MongoOperationsResolver."
            }
            val binding = requireNotNull(resolver.resolve(tenantId)) {
                "Tenant-aware MongoOperationsResolver returned no binding for tenant '$tenantId'."
            }
            check(binding.tenantId == tenantId) {
                "Tenant-aware MongoOperationsResolver returned binding '${binding.tenantId}' for requested tenant " +
                    "'$tenantId'."
            }
            binding.operations
        }
        return operations.findById(key, readModelType)
    }

    private fun snapshotClaims(mappingContext: MongoMappingContext): Map<Class<*>, EntityClaim> {
        val claims = mappingContext.persistentEntities
            .asSequence()
            .filter { it.type.isAnnotationPresent(ReadModel::class.java) }
            .filter { !it.type.isInterface && !Modifier.isAbstract(it.type.modifiers) }
            .sortedBy { it.type.name }
            .map { entity ->
                val type = entity.type
                require(!type.isPrimitive) { "MongoDB read model '${type.name}' must be a nonprimitive class." }
                val idProperty = checkNotNull(entity.idProperty) {
                    "MongoDB read model '${type.name}' must have a Spring Data MongoDB identifier."
                }
                type to EntityClaim(ClassUtils.resolvePrimitiveIfNecessary(idProperty.type))
            }
            .toList()
        return Collections.unmodifiableMap(LinkedHashMap<Class<*>, EntityClaim>().apply { putAll(claims) })
    }

    private data class EntityClaim(val idType: Class<*>)
}
