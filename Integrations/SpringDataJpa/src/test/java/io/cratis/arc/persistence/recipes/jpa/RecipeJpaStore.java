// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.persistence.recipes.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Application-owned mappings only; no scanning or converter installation during lookup. */
public final class RecipeJpaStore implements AutoCloseable {
    private final LocalContainerEntityManagerFactoryBean factoryBean;
    private final JdbcTemplate jdbc;

    public RecipeJpaStore(Class<?>... managedTypes) {
        var name = "arc-concept-recipe-" + UUID.randomUUID();
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPersistenceUnitName(name);
        factoryBean.setManagedTypes(PersistenceManagedTypes.of(
            Arrays.stream(managedTypes).map(Class::getName).toArray(String[]::new)));
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        try {
            factoryBean.afterPropertiesSet();
        } catch (RuntimeException failure) {
            jdbc.execute("SHUTDOWN");
            throw failure;
        }
    }

    public EntityManagerFactory factory() {
        return factoryBean.getNativeEntityManagerFactory();
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public void transaction(Consumer<EntityManager> action) {
        try (var manager = factory().createEntityManager()) {
            var transaction = manager.getTransaction();
            transaction.begin();
            try {
                action.accept(manager);
                transaction.commit();
            } finally {
                if (transaction.isActive()) transaction.rollback();
            }
        }
    }

    public <T> T read(Function<EntityManager, T> action) {
        try (var manager = factory().createEntityManager()) {
            return action.apply(manager);
        }
    }

    @Override
    public void close() {
        try {
            factoryBean.destroy();
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}
