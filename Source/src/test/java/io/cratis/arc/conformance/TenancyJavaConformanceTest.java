// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.identity.AsyncUsersProvider;
import io.cratis.arc.identity.AsyncUsersProviderAdapter;
import io.cratis.arc.identity.User;
import io.cratis.arc.identity.UsersProviderAggregator;
import io.cratis.arc.tenancy.AsyncTenantsProvider;
import io.cratis.arc.tenancy.AsyncTenantsProviderAdapter;
import io.cratis.arc.tenancy.CompositeTenantIdResolver;
import io.cratis.arc.tenancy.HeaderTenantIdResolver;
import io.cratis.arc.tenancy.QueryTenantIdResolver;
import io.cratis.arc.tenancy.Tenant;
import io.cratis.arc.tenancy.TenantId;
import io.cratis.arc.tenancy.TenantName;
import io.cratis.arc.tenancy.TenantResolutionContext;
import io.cratis.arc.tenancy.TenantsProviderAggregator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Runtime conformance for Java-first tenancy and development-provider APIs. */
final class TenancyJavaConformanceTest {
    @Test
    void resolversAndBlockingProviderBridgesAreJavaFriendly() {
        TenantResolutionContext context = new TenantResolutionContext(
            Map.of("X-Cratis-Tenant-Id", "header"),
            Map.of("tenantId", "query"));
        CompositeTenantIdResolver resolver = new CompositeTenantIdResolver(List.of(
            new QueryTenantIdResolver(),
            new HeaderTenantIdResolver()));

        assertEquals(new TenantId("query"), resolver.resolve(context));
        assertEquals("Default", TenantId.DEFAULT.value());

        ArcPrincipal principal = new ArcPrincipal("Ada", true, java.util.Set.of(), "user-one");
        AsyncUsersProvider asyncUsers = () -> CompletableFuture.completedFuture(List.of(new User(principal)));
        AsyncTenantsProvider asyncTenants = () -> CompletableFuture.completedFuture(List.of(
            new Tenant(new TenantId("tenant-one"), new TenantName("Tenant One"))));
        UsersProviderAggregator users = new UsersProviderAggregator(
            List.of(new AsyncUsersProviderAdapter(asyncUsers)));
        TenantsProviderAggregator tenants = new TenantsProviderAggregator(
            List.of(new AsyncTenantsProviderAdapter(asyncTenants)));

        assertEquals("user-one", users.provideBlocking().get(0).getPrincipal().getId());
        assertEquals(new TenantId("tenant-one"), tenants.provideBlocking().get(0).getId());
        assertThrows(
            UnsupportedOperationException.class,
            () -> users.provideBlocking().add(new User(principal)));
    }
}
