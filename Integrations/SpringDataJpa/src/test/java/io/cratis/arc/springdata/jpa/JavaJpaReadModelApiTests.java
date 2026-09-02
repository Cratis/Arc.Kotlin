// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandHandlerRegistry;
import io.cratis.arc.commands.ServiceResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.Type;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaJpaReadModelApiTests {
    @Test
    @SuppressWarnings("unchecked")
    void persistenceUnitResolverAndContextualProviderExecuteFromJava() {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);
        EntityManager entityManager = mock(EntityManager.class);
        Metamodel metamodel = mock(Metamodel.class);
        EntityType<JpaTaskReadModel> entity = mock(EntityType.class);
        Type<String> identifier = mock(Type.class);
        when(factory.getMetamodel()).thenReturn(metamodel);
        when(factory.createEntityManager()).thenReturn(entityManager);
        when(metamodel.getEntities()).thenReturn(Set.of(entity));
        when(entity.getJavaType()).thenReturn(JpaTaskReadModel.class);
        when(entity.hasSingleIdAttribute()).thenReturn(true);
        doReturn(identifier).when(entity).getIdType();
        when(identifier.getJavaType()).thenReturn(String.class);

        JpaTransactionManager transactionManager = new JpaTransactionManager(factory);
        JpaPersistenceUnit unit = JavaJpaApiAccess.fixedUnit(factory, transactionManager);
        JpaPersistenceUnitResolver unitResolver = new JpaPersistenceUnitResolver() {
            @Override
            public Set<Class<?>> readModelTypes() {
                return unit.readModelTypes();
            }

            @Override
            public JpaPersistenceUnit resolve(String tenantId, String tenantNamespace) {
                return "tenant-one".equals(tenantId) && "namespace-one".equals(tenantNamespace)
                    ? JpaPersistenceUnit.builder(factory)
                        .transactionManager(transactionManager)
                        .tenant("tenant-one", "namespace-one")
                        .build()
                    : null;
            }
        };
        JpaReadModelForCommandResolver provider = JavaJpaApiAccess.provider(unitResolver, true);
        JpaTaskReadModel expected = new JpaTaskReadModel("task-1", "Stored");
        when(entityManager.find(JpaTaskReadModel.class, "task-1")).thenReturn(expected);
        Object command = new Object();
        ServiceResolver services = new ServiceResolver() {
            @Override
            public <T> T resolve(Class<T> type) {
                return null;
            }
        };
        CommandContext context = new CommandContext(
            UUID.randomUUID(),
            command,
            command.getClass(),
            ArcPrincipal.anonymous(),
            "tenant-one",
            "namespace-one",
            services);

        assertEquals(Set.of(JpaTaskReadModel.class), provider.readModelTypes());
        assertSame(expected, JavaJpaApiAccess.resolveContextual(provider, context, "task-1"));

        CommandHandlerRegistry handlers = mock(CommandHandlerRegistry.class);
        JpaCommandReadModelResolver legacy = JavaJpaApiAccess.legacy(factory, handlers);
        assertEquals(DefaultJpaCommandReadModelResolver.class, legacy.getClass());
    }
}
