// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.commands.CommandFilter;
import io.cratis.arc.commands.CommandValidator;
import io.cratis.arc.identity.AsyncIdentityDetailsProviderAdapter;
import io.cratis.arc.identity.IdentityDetailsProvider;
import io.cratis.arc.java.BlockingCommandFilterAdapter;
import io.cratis.arc.java.BlockingCommandValidatorAdapter;
import io.cratis.arc.java.BlockingQueryFilterAdapter;
import io.cratis.arc.queries.QueryFilter;
import io.cratis.arc.springboot.ArcPrincipalFactory;
import io.cratis.arc.samples.javaspringboot.features.SampleTicker;
import io.cratis.arc.samples.javaspringboot.features.crosscuttingauthorization.CrossCuttingAuthorizationFilters;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

/** Runs the Java Spring Boot sample host. */
@SpringBootApplication
public class JavaSampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(JavaSampleApplication.class, args);
    }

    /** Adapts the ordinary Java validator to Arc's host-neutral command validation contract. */
    @Bean
    public CommandValidator<CreateTask> createTaskValidator() {
        return new BlockingCommandValidatorAdapter<>(new CreateTaskValidator());
    }

    /**
     * The default store: no container, no connection string, no setup.
     *
     * Stands down when a database profile is active, which is how {@code ./run.sh --database mongodb}
     * replaces the whole persistence layer without touching a command or a query. The condition is a
     * profile expression rather than {@code @ConditionalOnMissingBean}, which is only reliable inside
     * an auto-configuration: in an application configuration it is evaluated in bean-definition
     * order, so whether it sees the database store depends on component-scan order.
     *
     * @return The in-memory store.
     */
    @Bean
    @Profile("!mongodb & !postgres")
    public TaskStore inMemoryTaskStore() {
        return new TaskRepository();
    }

    /** One daemon scheduler shared by every feature that publishes on a timer. */
    @Bean(destroyMethod = "destroy")
    public SampleTicker sampleTicker() {
        return new SampleTicker();
    }

    /**
     * Captures the caller the frontend's sign-in toggle describes.
     *
     * With {@code cratis.arc.samples.default-identity=false} a request that carries no client
     * principal stays anonymous, which is the mode the Authentication Queries page is built to show.
     *
     * @param objectMapper Reads the decoded client principal.
     * @param defaultIdentity Whether an unidentified request becomes the built-in sample user.
     * @return The principal factory.
     */
    @Bean
    public ArcPrincipalFactory sampleArcPrincipalFactory(
        ObjectMapper objectMapper,
        @Value("${cratis.arc.samples.default-identity:true}") boolean defaultIdentity) {
        return new SampleArcPrincipalFactory(objectMapper, defaultIdentity);
    }

    /** Supplies the application-specific identity details served from {@code /.cratis/me}. */
    @Bean
    public IdentityDetailsProvider<SampleIdentityDetails> sampleIdentityDetailsProvider() {
        return new AsyncIdentityDetailsProviderAdapter<>(new SampleIdentityDetailsProvider());
    }

    /** Requires a role for every command in the cross-cutting authorization feature. */
    @Bean
    public CommandFilter crossCuttingAuthorizationCommandFilter() {
        return new BlockingCommandFilterAdapter(new CrossCuttingAuthorizationFilters.CommandFilter());
    }

    /** Requires the same role for every query in that feature. */
    @Bean
    public QueryFilter crossCuttingAuthorizationQueryFilter() {
        return new BlockingQueryFilterAdapter(new CrossCuttingAuthorizationFilters.QueryFilter());
    }
}
