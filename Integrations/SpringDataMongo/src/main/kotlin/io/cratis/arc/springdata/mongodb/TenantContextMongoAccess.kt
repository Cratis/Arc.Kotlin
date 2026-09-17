// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import com.mongodb.client.MongoCollection
import io.cratis.arc.naming.NamingPolicy
import io.cratis.arc.tenancy.TenantContextBridge
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.currentTenant
import org.springframework.data.mongodb.core.MongoOperations

/**
 * Injectable factory that provides tenant-bound [MongoOperations] and native [MongoCollection]
 * access resolved for the tenant of the current call.
 *
 * Mirrors the IOC wiring from Arc .NET's
 * `ServiceCollectionExtensions.AddCratisMongoDB`
 * (`Source/DotNET/MongoDB/ServiceCollectionExtensions.cs`), which registers:
 *
 * ```csharp
 * services.AddScoped(sp =>
 * {
 *     var client = sp.GetRequiredService<IMongoDBClientFactory>().Create();
 *     var databaseNameResolver = sp.GetRequiredService<IMongoDatabaseNameResolver>();
 *     return client.GetDatabase(databaseNameResolver.Resolve());
 * });
 * services.AddScoped(typeof(IMongoCollection<>), typeof(MongoCollectionAdapter<>));
 * ```
 *
 * On the JVM, [MongoOperations] (Spring Data) is used instead of the raw `IMongoDatabase`
 * because [TenantMongoOperations] already certifies a [MongoOperations] per tenant, and
 * [MongoOperations] provides Spring Data's type-mapping and full CRUD infrastructure.
 *
 * ## Per-call tenant resolution
 *
 * Each method reads the ambient tenant at the moment it is called — the tenant is never captured
 * at construction time. Specifically:
 *
 * - Kotlin coroutine paths ([operations], [collection]): read [currentTenant] from the
 *   [kotlin.coroutines.CoroutineContext].
 * - Java blocking paths ([operationsForCurrentTenant], [collectionForCurrentTenant]): read the
 *   [TenantContextBridge] [ThreadLocal] installed by [TenantContextBridge.withTenant].
 * - Explicit-tenant paths ([operations] with [TenantId], [collection] with [TenantId]): bypass
 *   the ambient context entirely; usable from both Kotlin and Java.
 *
 * A bean that captured the tenant at construction time would silently serve the wrong database for
 * every request from a different tenant. This class holds only the resolver and naming policy.
 *
 * ## Collection naming
 *
 * [collection] derives the collection name from [namingPolicy] (Arc's kernel-aligned English-plural
 * inflection), which is the correct policy for types whose collections are created by Chronicle
 * kernel projections. The returned [MongoCollection] is the raw MongoDB Java driver collection,
 * bypassing Spring Data's object mapping. For type-safe Spring Data CRUD, use [operations]
 * and call its `find`, `findAll`, `save`, etc. methods.
 *
 * ## Fallback when no tenant is present
 *
 * When no tenant is in the ambient context and no [fallbackTenantId] is configured, all resolution
 * methods throw [IllegalStateException]. Pass a non-null [fallbackTenantId]
 * (e.g. [TenantId.DEFAULT]) to silently fall back to that tenant when the context is empty —
 * useful for non-tenanted code paths or integration tests that run without a tenant scope.
 *
 * ## Real-server coverage
 *
 * Per-call tenant resolution and isolation are verified against both the `mongo-java-server` 1.47.0
 * `MemoryBackend` emulator and a real pinned MongoDB 8.2 single-node replica set via the
 * `ContractTests:mongoReplicaSetTest` gate. That gate proves that coroutine (`withTenant`) and
 * Java blocking (`TenantContextBridge`) calls from two different tenants reach genuinely isolated
 * MongoDB databases on a real server, and that change streams are available. Production
 * replica-set failover, TLS, and transactions are not covered by the current evidence.
 */
public class TenantContextMongoAccess @JvmOverloads constructor(
    private val resolver: TenantAwareMongoOperationsResolver,
    private val namingPolicy: NamingPolicy,
    private val fallbackTenantId: TenantId? = null
) {
    /**
     * Returns the [MongoOperations] for the tenant in the current coroutine context.
     *
     * Reads [currentTenant] from the coroutine's [kotlin.coroutines.CoroutineContext] on every
     * call. No tenant is captured at construction time.
     *
     * @throws IllegalStateException when no tenant is active and no [fallbackTenantId] is set.
     */
    public suspend fun operations(): MongoOperations = resolveFromContext(currentTenant())

    /**
     * Returns the [MongoOperations] for the tenant in an explicit [tenantId].
     *
     * Bypasses the ambient coroutine and blocking contexts. Callable from both Kotlin and Java.
     */
    public fun operations(tenantId: TenantId): MongoOperations = resolveForTenant(tenantId)

    /**
     * Returns the [MongoOperations] for the tenant established in the current blocking Java call
     * path via [TenantContextBridge.withTenant].
     *
     * Reads from the [TenantContextBridge] [ThreadLocal] that [TenantContextBridge.withTenant]
     * installs and tears down around its body. Do not call from within an active coroutine — use
     * [operations] instead; the [ThreadLocal] is not automatically kept in sync with the
     * coroutine context.
     *
     * @throws IllegalStateException when [TenantContextBridge.currentTenant] returns null and no
     *   [fallbackTenantId] is configured.
     */
    public fun operationsForCurrentTenant(): MongoOperations =
        resolveFromContext(TenantContextBridge.currentTenant())

    /**
     * Returns the raw [MongoCollection] for [documentType], named by [namingPolicy], for the
     * tenant in the current coroutine context.
     *
     * This is the JVM equivalent of `IMongoCollection<T>` in Arc .NET's `MongoCollectionAdapter<T>`:
     * it routes to the correct tenant's database and names the collection using the kernel-aligned
     * [NamingPolicy] inflection. The returned collection is a raw MongoDB Java driver
     * [MongoCollection] and bypasses Spring Data's object mapping. Prefer [operations] for
     * type-safe, mapping-aware CRUD.
     *
     * @throws IllegalStateException when no tenant is active and no [fallbackTenantId] is set.
     */
    public suspend fun <T : Any> collection(documentType: Class<T>): MongoCollection<T> =
        nativeCollection(operations(), documentType)

    /**
     * Returns the raw [MongoCollection] for [documentType] and an explicit [tenantId].
     *
     * Bypasses the ambient context. Callable from both Kotlin and Java.
     */
    public fun <T : Any> collection(tenantId: TenantId, documentType: Class<T>): MongoCollection<T> =
        nativeCollection(operations(tenantId), documentType)

    /**
     * Returns the raw [MongoCollection] for [documentType], named by [namingPolicy], for the
     * tenant established in the current blocking Java call path via [TenantContextBridge.withTenant].
     *
     * @see operationsForCurrentTenant for blocking-path contract and caveats.
     */
    public fun <T : Any> collectionForCurrentTenant(documentType: Class<T>): MongoCollection<T> =
        nativeCollection(operationsForCurrentTenant(), documentType)

    private fun resolveFromContext(tenantId: TenantId?): MongoOperations {
        val effective = tenantId ?: fallbackTenantId
            ?: error(
                "No tenant identifier is present in the current context and no fallbackTenantId is configured. " +
                    "Establish a tenant with withTenant() for Kotlin coroutine paths or " +
                    "TenantContextBridge.withTenant() for blocking Java paths."
            )
        return resolveForTenant(effective)
    }

    private fun resolveForTenant(tenantId: TenantId): MongoOperations {
        val binding = resolver.resolve(tenantId.value())
        check(binding.tenantId == tenantId.value()) {
            "TenantAwareMongoOperationsResolver returned binding for tenant '${binding.tenantId}' " +
                "when '${tenantId.value()}' was requested."
        }
        return binding.operations
    }

    private fun <T : Any> nativeCollection(
        operations: MongoOperations,
        documentType: Class<T>
    ): MongoCollection<T> {
        val collectionName = namingPolicy.getReadModelName(documentType)
        return operations.getCollection(collectionName).withDocumentClass(documentType)
    }
}
