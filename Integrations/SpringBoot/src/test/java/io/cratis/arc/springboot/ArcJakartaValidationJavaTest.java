// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.metadata.ParameterDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.queries.AsyncQueryPipeline;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryFilter;
import io.cratis.arc.queries.QueryPerformer;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.QueryResult;
import io.cratis.arc.validation.CreditCard;
import io.cratis.arc.validation.Phone;
import io.cratis.arc.validation.Url;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Java-consumer coverage for the automatic Jakarta query filter. */
final class ArcJakartaValidationJavaTest {
    @Test
    void rejectsNestedJavaRecordBeforePerforming() throws Exception {
        JavaPerformer performer = new JavaPerformer();
        ConcurrentQueryPerformerRegistry registry = new ConcurrentQueryPerformerRegistry();
        registry.register(performer);
        QueryFilter filter = new ArcValidationAutoConfiguration().arcJakartaBeanValidationQueryFilter(
            Validation.buildDefaultValidatorFactory().getValidator(),
            registry);
        DefaultQueryPipeline pipeline = new DefaultQueryPipeline(registry, List.of(filter));
        CoroutineScope scope = CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault());
        AsyncQueryPipeline async = new AsyncQueryPipeline(pipeline, scope);

        QueryResult<?> result = async.perform(
            new QueryRequest(performer.getFullyQualifiedName(), Map.of("request", new JavaRequest(new JavaNested("")))),
            new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), EmptyServices.INSTANCE))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        assertFalse(result.isSuccess());
        assertEquals(List.of("request.nested.name"), result.getValidationResults().get(0).getMembers());
        assertEquals(0, performer.invocations.get());
    }

    @Test
    void ArcStringConstraintsAreUsableFromJavaRecords() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var invalid = validator.validate(new JavaArcConstraints("555.0100", "ftp://example.com", "4111111111111112"));
        var valid = validator.validate(
            new JavaArcConstraints("+1 (555) 010-0200", "HTTPS://example.com", "4111-1111 1111-1111"));

        assertEquals(
            Set.of("phone", "url", "creditCard"),
            invalid.stream().map(violation -> violation.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(
            Set.of("must be a valid phone number", "must be a valid URL", "must be a valid credit card number"),
            invalid.stream().map(violation -> violation.getMessage()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(), valid);
    }

    /** Java record exercising Arc's Jakarta-compatible validation annotations. */
    public record JavaArcConstraints(@Phone String phone, @Url String url, @CreditCard String creditCard) { }

    /** Query declaration used only for portable executable-constraint discovery. */
    public static final class JavaQueryDefinition {
        /** Stable receiver for executable validation. */
        public static final JavaQueryDefinition INSTANCE = new JavaQueryDefinition();

        /** Declares cascaded query validation. */
        public void validate(@Valid JavaRequest request) {
            // Query execution is represented by the generated-style performer below.
        }
    }

    /** Java query argument with a nested Jakarta-valid model. */
    public record JavaRequest(@Valid JavaNested nested) { }

    /** Nested Java model. */
    public record JavaNested(@NotBlank(message = "Name is required.") String name) { }

    private static final class JavaPerformer implements QueryPerformer {
        private final FullyQualifiedQueryName name = new FullyQualifiedQueryName(
            JavaQueryDefinition.class.getName() + ".validate");
        private final QueryDescriptor descriptor = new QueryDescriptor(
            "validate",
            JavaQueryDefinition.class.getName(),
            "java.lang.String",
            List.of(new ParameterDescriptor(
                "request",
                JavaRequest.class.getName(),
                false,
                false,
                false,
                null,
                List.of(),
                true)));
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public QueryDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public FullyQualifiedQueryName getFullyQualifiedName() {
            return name;
        }

        @Override
        public Object perform(QueryContext context, Continuation<? super Object> continuation) {
            invocations.incrementAndGet();
            return "performed";
        }
    }

    private enum EmptyServices implements ServiceResolver {
        INSTANCE;

        @Override
        public <T> T resolve(Class<T> type) {
            return null;
        }
    }
}
