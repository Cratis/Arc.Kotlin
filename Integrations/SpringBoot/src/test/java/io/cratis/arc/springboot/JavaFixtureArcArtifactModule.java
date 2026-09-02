// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandHandler;
import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.ParameterDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.QueryParameterSource;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.metadata.TypeShapeDescriptor;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryHttpMethodType;
import io.cratis.arc.queries.QueryPerformer;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryTransportType;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.coroutines.Continuation;

/** Java fixture proving generated-module, handler, and query performer contracts are straightforward Java APIs. */
public final class JavaFixtureArcArtifactModule extends ArcArtifactModule {
    /** Number of successful command handler invocations. */
    public static final AtomicInteger INVOCATIONS = new AtomicInteger();

    /** Context captured by the most recent successful command. */
    public static volatile CommandContext capturedContext;

    /** Context captured by the most recent typed query. */
    public static volatile QueryContext capturedQueryContext;

    /** Creates the Java fixture artifact module. */
    public JavaFixtureArcArtifactModule() {
        super(
            List.of(
                handler(JavaFixtureCommand.class, true, List.of(), true),
                handler(SecuredCommand.class, false, List.of("admin"), false),
                handler(ExplodingCommand.class, true, List.of(), false)),
            List.of(
                query("coexisting", "/api/fixtures/java-fixture-command", List.of(), anonymous(), QueryBehavior.COEXISTING),
                query("exploding", "/api/fixtures/exploding-query", List.of(), anonymous(), QueryBehavior.EXPLODING),
                query("notReady", "/api/fixtures/not-ready-query", List.of(), anonymous(), QueryBehavior.NOT_READY),
                query("secured", "/api/fixtures/secured-query", List.of(), secured(), QueryBehavior.SECURED),
                query(
                    "typed",
                    "/api/fixtures/typed-query",
                    List.of(
                        parameter("name", "kotlin.String"),
                        parameter("count", "kotlin.Int"),
                        parameter("active", "kotlin.Boolean"),
                        parameter("identifier", "java.util.UUID"),
                        parameter("state", QueryState.class.getName()),
                        parameter("date", "java.time.LocalDate"),
                        parameter("time", "java.time.LocalTime"),
                        parameter("times", "kotlin.Array<java.time.LocalTime>"),
                        parameter("instant", "java.time.Instant"),
                        parameter("ids", "java.util.List<java.lang.Integer>"),
                        parameter("codes", "kotlin.Array<kotlin.String>"),
                        parameter("queryId", QueryId.class.getName()),
                        new ParameterDescriptor("dependency", QueryFixtureService.class.getName(), false, true),
                        new ParameterDescriptor(
                            "request",
                            TypeShapeDescriptor.value(QueryRequest.class.getName()),
                            QueryParameterSource.QUERY_REQUEST),
                        new ParameterDescriptor(
                            "context",
                            TypeShapeDescriptor.value(QueryContext.class.getName()),
                            QueryParameterSource.QUERY_CONTEXT)),
                    anonymous(),
                    QueryBehavior.TYPED),
                query(
                    "validated",
                    "/api/fixtures/validated-query",
                    List.of(parameter("value", "kotlin.String")),
                    anonymous(),
                    QueryBehavior.VALIDATED)));
    }

    private static CommandHandler handler(
        Class<?> commandType,
        boolean allowAnonymous,
        List<String> roles,
        boolean treatWarningsAsErrors) {
        return new JavaCommandHandler(commandType, allowAnonymous, roles, treatWarningsAsErrors);
    }

    private static ParameterDescriptor parameter(String name, String typeName) {
        return new ParameterDescriptor(name, typeName, false, false);
    }

    private static AuthorizationMetadata anonymous() {
        return new AuthorizationMetadata(true, null, List.of(), List.of());
    }

    private static AuthorizationMetadata secured() {
        return new AuthorizationMetadata(false, null, List.of("admin"), List.of());
    }

    private static QueryPerformer query(
        String name,
        String path,
        List<ParameterDescriptor> parameters,
        AuthorizationMetadata authorization,
        QueryBehavior behavior) {
        return new JavaQueryPerformer(name, path, parameters, authorization, behavior);
    }

    /** Java command deserialized and invoked by the servlet host. */
    public record JavaFixtureCommand(@NotBlank String value) { }

    /** Typed Java command response. */
    public record JavaFixtureResponse(String message, List<String> items) { }

    /** Command requiring an authenticated caller in the admin role. */
    public record SecuredCommand(String value) { }

    /** Command whose handler fails with internal detail. */
    public record ExplodingCommand(String value) { }

    /** Java ConceptAs fixture with a public single-value constructor. */
    public record QueryId(UUID value) implements io.cratis.arc.concepts.ConceptAs<UUID> { }

    /** Enum fixture for name and numeric conversion. */
    public enum QueryState {
        /** First fixture state. */
        OPEN,
        /** Second fixture state. */
        CLOSED
    }

    /** Spring service resolved by the Java query performer. */
    public static final class QueryFixtureService {
        /** Returns a stable marker proving service resolution. */
        public String marker() {
            return "spring-service";
        }
    }

    private static final class JavaCommandHandler implements CommandHandler {
        private final Class<?> commandType;
        private final CommandDescriptor metadata;

        private JavaCommandHandler(
            Class<?> commandType,
            boolean allowAnonymous,
            List<String> roles,
            boolean treatWarningsAsErrors) {
            this.commandType = commandType;
            this.metadata = new CommandDescriptor(
                commandType.getSimpleName(),
                commandType.getName(),
                List.of(),
                new RouteOptions(),
                List.of("fixtures"),
                new AuthorizationMetadata(allowAnonymous, null, roles, List.of()),
                null,
                treatWarningsAsErrors);
        }

        @Override
        public Class<?> getCommandType() {
            return commandType;
        }

        @Override
        public CommandDescriptor getMetadata() {
            return metadata;
        }

        @Override
        public boolean getAllowsAnonymous() {
            return metadata.getAuthorization().getAllowAnonymous();
        }

        @Override
        public Object invoke(CommandContext context, Continuation<? super Object> continuation) {
            if (commandType == ExplodingCommand.class) {
                throw new IllegalStateException("secret-java-handler-detail");
            }
            if (commandType == JavaFixtureCommand.class) {
                INVOCATIONS.incrementAndGet();
                capturedContext = context;
                JavaFixtureCommand command = (JavaFixtureCommand) context.getCommand();
                return new JavaFixtureResponse("handled:" + command.value(), List.of());
            }
            return null;
        }
    }

    private enum QueryBehavior {
        COEXISTING,
        EXPLODING,
        NOT_READY,
        SECURED,
        TYPED,
        VALIDATED
    }

    private static final class JavaQueryPerformer implements QueryPerformer {
        private static final String DECLARING_TYPE = "io.cratis.arc.springboot.JavaQueryFixture";
        private final FullyQualifiedQueryName fullyQualifiedName;
        private final QueryDescriptor descriptor;
        private final QueryBehavior behavior;

        private JavaQueryPerformer(
            String name,
            String path,
            List<ParameterDescriptor> parameters,
            AuthorizationMetadata authorization,
            QueryBehavior behavior) {
            fullyQualifiedName = new FullyQualifiedQueryName(DECLARING_TYPE + "." + name);
            descriptor = new QueryDescriptor(
                name,
                DECLARING_TYPE,
                "java.lang.Object",
                parameters,
                new RouteOptions(path),
                fullyQualifiedName.getValue(),
                List.of("fixtures"),
                authorization,
                path,
                QueryHttpMethodType.GET,
                QueryTransportType.REQUEST_RESPONSE,
                false,
                false,
                false);
            this.behavior = behavior;
        }

        @Override
        public FullyQualifiedQueryName getFullyQualifiedName() {
            return fullyQualifiedName;
        }

        @Override
        public QueryDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public boolean getAllowsAnonymous() {
            return descriptor.getAuthorization().getAllowAnonymous();
        }

        @Override
        public boolean getSupportsPaging() {
            return descriptor.getSupportsPaging();
        }

        @Override
        public Object perform(QueryContext context, Continuation<? super Object> continuation) {
            return switch (behavior) {
                case COEXISTING -> Map.of("transport", "query");
                case EXPLODING -> throw new IllegalStateException("secret-java-query-detail");
                case NOT_READY -> io.cratis.arc.results.QueryResult.notReady(context.getCorrelationId());
                case SECURED -> Map.of("authorized", true);
                case TYPED -> typed(context);
                case VALIDATED -> Map.of("value", context.getRequest().getArguments().get("value"));
            };
        }

        private static Map<String, Object> typed(QueryContext context) {
            capturedQueryContext = context;
            Map<String, Object> arguments = context.getRequest().getArguments();
            QueryFixtureService dependency = context.getServiceResolver().resolve(QueryFixtureService.class);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.putAll(arguments);
            result.put("service", dependency == null ? "missing" : dependency.marker());
            result.put("page", context.getRequest().getPaging().getPage());
            result.put("pageSize", context.getRequest().getPaging().getPageSize());
            result.put("sortField", context.getRequest().getSorting().getField());
            result.put("sortDirection", context.getRequest().getSorting().getDirection().name());
            result.put("tenant", context.getTenantId() == null ? "" : context.getTenantId());
            result.put("principal", context.getPrincipal().getName() == null ? "" : context.getPrincipal().getName());
            return result;
        }
    }
}
