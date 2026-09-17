// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import io.cratis.arc.tenancy.TenantContextBridge;
import io.cratis.arc.tenancy.TenantId;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Java-facing conformance test for {@link TenantContextMongoAccess}.
 *
 * Exercises the public API as a plain Java consumer would call it — no Kotlin default
 * arguments, no suspend functions, no Kotlin extension syntax.
 */
final class JavaTenantContextMongoAccessTests {

    @Test
    void explicitTenantOverloadIsCallableFromJavaWithoutAmbientContext() {
        var opsA = mock(MongoOperations.class);
        var opsB = mock(MongoOperations.class);
        var access = buildAccess(opsA, opsB);

        var resolvedA = access.operations(TenantId.of("tenant-a"));
        var resolvedB = access.operations(TenantId.of("tenant-b"));

        assertSame(opsA, resolvedA);
        assertSame(opsB, resolvedB);
    }

    @Test
    void blockingPathResolvesCorrectTenantViaBridge() {
        var opsA = mock(MongoOperations.class);
        var opsB = mock(MongoOperations.class);
        var access = buildAccess(opsA, opsB);

        var resolvedA = TenantContextBridge.withTenant(TenantId.of("tenant-a"), (Callable<MongoOperations>) access::operationsForCurrentTenant);
        var resolvedB = TenantContextBridge.withTenant(TenantId.of("tenant-b"), (Callable<MongoOperations>) access::operationsForCurrentTenant);

        assertSame(opsA, resolvedA);
        assertSame(opsB, resolvedB);
    }

    @Test
    void twoTenantsBridgedSequentiallyThroughSameAccessInstanceStayIsolated() {
        var opsA = mock(MongoOperations.class);
        var opsB = mock(MongoOperations.class);
        var access = buildAccess(opsA, opsB);

        // Both calls go through the same injected access; the bridge must use the correct tenant
        // on each call, with no bleed-through from the previous invocation.
        MongoOperations[] results = new MongoOperations[2];
        TenantContextBridge.withTenant(TenantId.of("tenant-a"), () -> {
            results[0] = access.operationsForCurrentTenant();
        });
        TenantContextBridge.withTenant(TenantId.of("tenant-b"), () -> {
            results[1] = access.operationsForCurrentTenant();
        });

        assertSame(opsA, results[0]);
        assertSame(opsB, results[1]);
    }

    @Test
    void noTenantOutsideBridgeScopeThrowsIllegalStateException() {
        var access = buildAccess(mock(MongoOperations.class), mock(MongoOperations.class));
        assertThrows(IllegalStateException.class, access::operationsForCurrentTenant);
    }

    @Test
    void twoArgConstructorOmittingFallbackIsCallableFromJava() {
        // @JvmOverloads generates a two-argument constructor; verify Java can use the short form.
        // One stable instance, because the resolver is consulted on every call by design - a
        // resolver that returned a fresh instance per call would make this assertion meaningless.
        var operations = mock(MongoOperations.class);
        var access = new TenantContextMongoAccess(
            tenantId -> new TenantMongoOperations(tenantId, operations),
            new DefaultNamingPolicy()
        );
        // Explicit overload must succeed without a context, and must pass the resolved instance
        // through unchanged on each call.
        assertSame(operations, access.operations(TenantId.of("any")));
        assertSame(operations, access.operations(TenantId.of("any")));
    }

    @Test
    void collectionExplicitTenantOverloadIsCallableFromJava() {
        var opsA = mock(MongoOperations.class);
        @SuppressWarnings("unchecked")
        com.mongodb.client.MongoCollection<org.bson.Document> raw =
            mock(com.mongodb.client.MongoCollection.class);
        @SuppressWarnings("unchecked")
        com.mongodb.client.MongoCollection<MongoTaskReadModel> typed =
            mock(com.mongodb.client.MongoCollection.class);
        when(raw.withDocumentClass(MongoTaskReadModel.class)).thenReturn(typed);
        when(opsA.getCollection("MongoTaskReadModels")).thenReturn(raw);
        var access = buildAccess(opsA, mock(MongoOperations.class));

        // Java can call the explicit-tenant collection() overload without a coroutine scope, and the
        // collection name must come from the naming policy rather than the raw simple name.
        assertSame(typed, access.collection(TenantId.of("tenant-a"), MongoTaskReadModel.class));
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private static TenantContextMongoAccess buildAccess(
        MongoOperations opsA,
        MongoOperations opsB
    ) {
        TenantAwareMongoOperationsResolver resolver = tenantId -> switch (tenantId) {
            case "tenant-a" -> new TenantMongoOperations(tenantId, opsA);
            case "tenant-b" -> new TenantMongoOperations(tenantId, opsB);
            default -> throw new IllegalArgumentException("Unknown tenant " + tenantId);
        };
        return new TenantContextMongoAccess(resolver, new DefaultNamingPolicy());
    }
}
