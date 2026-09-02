// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import io.cratis.arc.commands.CommandHandlerRegistry;
import io.cratis.arc.queries.BlockingReadModelForCommandResolver;
import io.cratis.arc.queries.QueryPage;
import io.cratis.arc.queries.QueryRequest;
import org.springframework.data.domain.Page;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;

public final class JavaMongoApiAccess {
    private JavaMongoApiAccess() {
    }

    public static Pageable pageable(QueryRequest request) {
        return MongoQueryRequestAdapter.toPageable(request);
    }

    public static <T> QueryPage<T> page(Page<T> page) {
        return MongoQueryPageAdapter.toQueryPage(page);
    }

    public static MongoTaskReadModel resolve(
        MongoCommandReadModelResolver resolver,
        Object command
    ) {
        return resolver.resolve(MongoTaskReadModel.class, command);
    }

    public static TenantAwareMongoOperationsResolver tenantAware(
        MongoOperations tenantA,
        MongoOperations tenantB
    ) {
        return tenantId -> switch (tenantId) {
            case "tenant-a" -> new TenantMongoOperations(tenantId, tenantA);
            case "tenant-b" -> new TenantMongoOperations(tenantId, tenantB);
            default -> throw new IllegalArgumentException("Unknown tenant " + tenantId);
        };
    }

    public static MongoReadModelForCommandResolver provider(
        MongoMappingContext mappingContext,
        TenantAwareMongoOperationsResolver operationsResolver,
        boolean tenancyRequired
    ) {
        return new MongoReadModelForCommandResolver(mappingContext, operationsResolver, tenancyRequired);
    }

    public static BlockingReadModelForCommandResolver asBlocking(MongoReadModelForCommandResolver resolver) {
        return resolver;
    }

    public static DefaultMongoCommandReadModelResolver legacy(
        MongoOperations operations,
        MongoMappingContext mappingContext,
        CommandHandlerRegistry handlers
    ) {
        return new DefaultMongoCommandReadModelResolver(operations, mappingContext, handlers);
    }

    public static Flow.Publisher<List<MongoTaskReadModel>> observePublisher(MongoObservableQuery queries) {
        return queries.observePublisher(MongoTaskReadModel.class, new Query(), null);
    }

    public static AutoCloseable observe(
        MongoObservableQuery queries,
        Consumer<List<MongoTaskReadModel>> callback
    ) {
        return queries.observe(MongoTaskReadModel.class, new Query(), null, callback);
    }
}
