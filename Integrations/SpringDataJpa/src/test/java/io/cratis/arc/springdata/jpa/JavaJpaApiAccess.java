// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa;

import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandHandlerRegistry;
import io.cratis.arc.queries.QueryPage;
import io.cratis.arc.queries.QueryRequest;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.data.domain.Page;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import org.springframework.data.domain.Pageable;

public final class JavaJpaApiAccess {
    private JavaJpaApiAccess() {
    }

    public static Pageable pageable(QueryRequest request) {
        return JpaQueryRequestAdapter.toPageable(request);
    }

    public static <T> QueryPage<T> page(Page<T> page) {
        return JpaQueryPageAdapter.toQueryPage(page);
    }

    public static JpaTaskReadModel resolve(
        JpaCommandReadModelResolver resolver,
        Object command
    ) {
        return resolver.resolve(JpaTaskReadModel.class, command);
    }

    public static JpaPersistenceUnit fixedUnit(
        EntityManagerFactory entityManagerFactory,
        JpaTransactionManager transactionManager
    ) {
        return JpaPersistenceUnit.builder(entityManagerFactory)
            .transactionManager(transactionManager)
            .build();
    }

    public static JpaReadModelForCommandResolver provider(
        JpaPersistenceUnitResolver resolver,
        boolean tenancyRequired
    ) {
        return new JpaReadModelForCommandResolver(resolver, tenancyRequired);
    }

    public static JpaTaskReadModel resolveContextual(
        JpaReadModelForCommandResolver resolver,
        CommandContext context,
        Object key
    ) {
        return (JpaTaskReadModel) resolver.resolveBlocking(JpaTaskReadModel.class, context, key);
    }

    public static JpaCommandReadModelResolver legacy(
        EntityManagerFactory entityManagerFactory,
        CommandHandlerRegistry handlers
    ) {
        return new DefaultJpaCommandReadModelResolver(entityManagerFactory, handlers);
    }

    public static Flow.Publisher<List<JpaTaskReadModel>> observePublisher(JpaObservableQuery queries) {
        JpaSnapshotQuery<JpaTaskReadModel> query = entityManager ->
            entityManager.createQuery("select task from JpaTaskReadModel task", JpaTaskReadModel.class).getResultList();
        return queries.observePublisher(JpaTaskReadModel.class, query, null);
    }

    public static AutoCloseable observe(
        JpaObservableQuery queries,
        Consumer<List<JpaTaskReadModel>> callback
    ) {
        JpaSnapshotQuery<JpaTaskReadModel> query = entityManager ->
            entityManager.createQuery("select task from JpaTaskReadModel task", JpaTaskReadModel.class).getResultList();
        return queries.observe(JpaTaskReadModel.class, query, null, callback);
    }
}
