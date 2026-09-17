// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.tenancy.TenantContextBridge;
import io.cratis.arc.tenancy.TenantId;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Java-facing conformance checks for the ambient tenancy bridge.
 *
 * <p>Verifies that {@link TenantContextBridge} is usable from idiomatic Java without
 * Kotlin-specific call patterns: plain lambda {@link Callable} and {@link Runnable},
 * static {@code withTenant} and {@code currentTenant} calls, and absence behaviour
 * when no scope is active.
 */
final class TenantAmbientContextJavaConformanceTest {

    @Test
    void currentTenantReturnsNullWhenNoBridgeScopeIsActive() {
        assertNull(TenantContextBridge.currentTenant());
    }

    @Test
    void withTenantCallableExposesTheTenantInsideTheScope() {
        TenantId tenantId = new TenantId("java-tenant");
        TenantId observed = TenantContextBridge.withTenant(tenantId, () ->
            TenantContextBridge.currentTenant()
        );
        assertEquals(tenantId, observed);
    }

    @Test
    void withTenantRunnableExposesTheTenantInsideTheScope() {
        TenantId tenantId = new TenantId("java-runnable-tenant");
        TenantId[] observed = { null };
        TenantContextBridge.withTenant(tenantId, () ->
            observed[0] = TenantContextBridge.currentTenant()
        );
        assertEquals(tenantId, observed[0]);
    }

    @Test
    void withTenantCallableReturnsTheValueProducedByTheBlock() throws Exception {
        TenantId tenantId = new TenantId("return-tenant");
        Callable<String> block = () -> "produced";
        String result = TenantContextBridge.withTenant(tenantId, block);
        assertEquals("produced", result);
    }

    @Test
    void tenantIsNullAfterTheBridgeScopeExits() {
        TenantContextBridge.withTenant(new TenantId("ephemeral"), () -> { /* no-op */ });
        assertNull(TenantContextBridge.currentTenant());
    }

    @Test
    void withTenantScopesDoNotLeakIntoEachOther() {
        TenantId first = new TenantId("first");
        TenantId second = new TenantId("second");

        TenantId[] observedFirst = { null };
        TenantId[] observedSecond = { null };

        TenantContextBridge.withTenant(first, () ->
            observedFirst[0] = TenantContextBridge.currentTenant()
        );
        TenantContextBridge.withTenant(second, () ->
            observedSecond[0] = TenantContextBridge.currentTenant()
        );

        assertEquals(first, observedFirst[0]);
        assertEquals(second, observedSecond[0]);
        assertNull(TenantContextBridge.currentTenant());
    }
}
