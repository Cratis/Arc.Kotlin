// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import io.cratis.arc.tenancy.TenantId;
import io.cratis.fundamentals.serialization.DefaultNamingPolicy;
import io.cratis.fundamentals.serialization.NamingPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Java-facing conformance checks for the Fundamentals {@link NamingPolicy} accepted by
 * {@link TenantContextMongoAccess}.
 */
final class FundamentalsNamingPolicyJavaConformanceTests {
    @Test
    void fundamentalsDefaultPolicyPluralisesFromJava() {
        NamingPolicy policy = new DefaultNamingPolicy();
        assertEquals("MongoTaskReadModels", policy.getReadModelName(MongoTaskReadModel.class));
        assertEquals("name", policy.getPropertyName("name"));
    }

    @Test
    void fundamentalsLambdaPolicyIsAcceptedByTheTwoArgumentConstructor() {
        var operations = mock(MongoOperations.class);
        TenantAwareMongoOperationsResolver resolver = tenantId -> new TenantMongoOperations(tenantId, operations);
        NamingPolicy policy = type -> "lambda";
        var access = new TenantContextMongoAccess(resolver, policy);

        assertSame(operations, access.operations(TenantId.of("tenant-a")));
    }

    @Test
    void fundamentalsPolicyIsAcceptedByTheThreeArgumentConstructor() {
        var operations = mock(MongoOperations.class);
        TenantAwareMongoOperationsResolver resolver = tenantId -> new TenantMongoOperations(tenantId, operations);
        var access = new TenantContextMongoAccess(resolver, new DefaultNamingPolicy(), TenantId.of("fallback"));

        assertSame(operations, access.operationsForCurrentTenant());
    }

    @Test
    void collectionNameComesFromTheFundamentalsPolicy() {
        var operations = mock(MongoOperations.class);
        @SuppressWarnings("unchecked")
        com.mongodb.client.MongoCollection<org.bson.Document> raw = mock(com.mongodb.client.MongoCollection.class);
        @SuppressWarnings("unchecked")
        com.mongodb.client.MongoCollection<MongoTaskReadModel> typed = mock(com.mongodb.client.MongoCollection.class);
        when(raw.withDocumentClass(MongoTaskReadModel.class)).thenReturn(typed);
        when(operations.getCollection("MongoTaskReadModels")).thenReturn(raw);
        TenantAwareMongoOperationsResolver resolver = tenantId -> new TenantMongoOperations(tenantId, operations);
        var access = new TenantContextMongoAccess(resolver, new DefaultNamingPolicy());

        assertSame(typed, access.collection(TenantId.of("tenant-a"), MongoTaskReadModel.class));
    }
}
