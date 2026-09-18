// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.persistence;

import io.cratis.arc.samples.javaspringboot.TaskStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Selects the database-backed task store the active profile asks for. */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {
    /**
     * Stores tasks in MongoDB.
     *
     * @param documents The Spring Data repository.
     * @return The store, which makes the in-memory one back off.
     */
    @Bean
    @Profile("mongodb")
    public TaskStore mongoTaskStore(TaskDocuments documents) {
        return new MongoTaskStore(documents);
    }

    /**
     * Stores tasks in a relational database.
     *
     * @param entities The Spring Data repository.
     * @return The store, which makes the in-memory one back off.
     */
    @Bean
    @Profile("postgres")
    public TaskStore jpaTaskStore(TaskEntities entities) {
        return new JpaTaskStore(entities);
    }
}
