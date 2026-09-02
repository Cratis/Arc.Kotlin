// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cratis.arc.ExceptionDetailRedactor;
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.artifacts.TreatWarningsAsErrors;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.authorization.AuthorizationEvaluator;
import io.cratis.arc.authorization.AuthorizationPolicy;
import io.cratis.arc.authorization.AuthorizationPolicyRegistry;
import io.cratis.arc.authorization.Authorize;
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry;
import io.cratis.arc.authorization.Roles;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.CommandExecutionToken;
import io.cratis.arc.commands.CommandHandler;
import io.cratis.arc.commands.CommandHandlerRegistry;
import io.cratis.arc.commands.CommandValidator;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.DefaultCommandValidationFilter;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.concepts.ArcEnum;
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.http.ArcHttpStatus;
import io.cratis.arc.http.ArcHttpStatusMapper;
import io.cratis.arc.identity.AsyncIdentityDetailsProvider;
import io.cratis.arc.identity.AsyncIdentityDetailsProviderAdapter;
import io.cratis.arc.identity.IdentityClaim;
import io.cratis.arc.identity.IdentityConstants;
import io.cratis.arc.identity.IdentityDetails;
import io.cratis.arc.identity.IdentityDetailsProvider;
import io.cratis.arc.identity.IdentityProviderContext;
import io.cratis.arc.identity.IdentityProviderResult;
import io.cratis.arc.identity.AsyncUsersProvider;
import io.cratis.arc.identity.AsyncUsersProviderAdapter;
import io.cratis.arc.identity.User;
import io.cratis.arc.identity.UsersProviderAggregator;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.json.ArcCamelCase;
import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.metadata.ApiEndpointOptions;
import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.EndpointRouteHelper;
import io.cratis.arc.metadata.ParameterDescriptor;
import io.cratis.arc.metadata.PropertyDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry;
import io.cratis.arc.polymorphism.DerivedType;
import io.cratis.arc.polymorphism.DerivedTypeRegistry;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.DefaultQueryValidationFilter;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.Path;
import io.cratis.arc.queries.QueryHttpMethod;
import io.cratis.arc.queries.QueryHttpMethodType;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryPaging;
import io.cratis.arc.queries.QueryPerformer;
import io.cratis.arc.queries.QueryPerformerRegistry;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QuerySortDirection;
import io.cratis.arc.queries.QuerySorting;
import io.cratis.arc.queries.QueryTransport;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.queries.QueryValidator;
import io.cratis.arc.results.ChangeSet;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.PagingInfo;
import io.cratis.arc.results.QueryResult;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.results.ValidationResultReasons;
import io.cratis.arc.results.ValidationResultSeverity;
import io.cratis.arc.tenancy.AsyncTenantsProvider;
import io.cratis.arc.tenancy.AsyncTenantsProviderAdapter;
import io.cratis.arc.tenancy.ClaimTenantIdResolver;
import io.cratis.arc.tenancy.CompositeTenantIdResolver;
import io.cratis.arc.tenancy.HeaderTenantIdResolver;
import io.cratis.arc.tenancy.QueryStringTenantIdResolver;
import io.cratis.arc.tenancy.SubdomainTenantIdResolver;
import io.cratis.arc.tenancy.TenancyOptions;
import io.cratis.arc.tenancy.Tenant;
import io.cratis.arc.tenancy.TenantId;
import io.cratis.arc.tenancy.TenantName;
import io.cratis.arc.tenancy.TenantResolutionContext;
import io.cratis.arc.tenancy.TenantsProviderAggregator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import kotlinx.coroutines.CoroutineScope;

/** Compile-only fixture for Arc's public Java consumer surface. */
public final class JavaConformance {
    private JavaConformance() {
    }

    /** Exercises descriptors and their Java getters. */
    public static String descriptors() {
        PropertyDescriptor property = new PropertyDescriptor("id", OrderId.class.getName(), false, true);
        RouteOptions route = new RouteOptions("/orders", QueryTransportType.REQUEST_RESPONSE);
        CommandDescriptor command = new CommandDescriptor(
            "CreateOrder",
            CreateOrder.class.getName(),
            List.of(property),
            route);
        ParameterDescriptor parameter = new ParameterDescriptor("id", OrderId.class.getName(), false, false);
        List<String> location = List.of("io", "cratis", "orders");
        AuthorizationMetadata authorization = new AuthorizationMetadata(
            false,
            "orders",
            List.of("operator, admin"),
            List.of("bearer"));
        QueryDescriptor query = new QueryDescriptor(
            "byId",
            Orders.class.getName(),
            OrderView.class.getName(),
            List.of(parameter),
            route,
            Orders.class.getName() + ".byId",
            location,
            authorization,
            "/orders/{id}",
            QueryHttpMethodType.GET,
            QueryTransportType.REQUEST_RESPONSE,
            true,
            true,
            true);
        ApiEndpointOptions endpointOptions = new ApiEndpointOptions("api", 1, true, true, true);
        String endpoint = EndpointRouteHelper.queryRoute(query, endpointOptions, false);
        return command.getName() + query.getFullyQualifiedName() + property.getName()
            + parameter.getName() + route.getPath() + endpoint;
    }

    /** Exercises host-neutral authorization and validator seams. */
    public static Object seams(
        AuthorizationPolicy policy,
        CommandValidator<CreateOrder> commandValidator,
        QueryValidator queryValidator) {
        AuthorizationPolicyRegistry policies = new ConcurrentAuthorizationPolicyRegistry();
        policies.register("orders", policy);
        AuthorizationEvaluator evaluator = new AuthorizationEvaluator(policies);
        DefaultCommandValidationFilter commandFilter = new DefaultCommandValidationFilter(List.of(commandValidator));
        DefaultQueryValidationFilter queryFilter = new DefaultQueryValidationFilter(List.of(queryValidator));
        return List.of(evaluator, commandFilter, queryFilter, commandValidator.getCommandType());
    }

    /** Exercises result factories and Java-visible properties. */
    public static ArcHttpStatus results(UUID correlationId) {
        ValidationResult validation = new ValidationResult(
            ValidationResultSeverity.Error,
            "invalid",
            List.of("id"),
            null,
            ValidationResultReasons.RULE,
            null);
        CommandResult<Void> success = CommandResult.success(correlationId);
        CommandResult<OrderId> response = CommandResult.success(correlationId, new OrderId(correlationId));
        CommandResult<Void> invalid = CommandResult.invalid(correlationId, List.of(validation));
        CommandResult<Void> malformed = CommandResult.malformed(correlationId);
        CommandResult<Void> unauthorized = CommandResult.unauthorized(correlationId, "role required");
        CommandResult<Void> exception = CommandResult.exception(correlationId, new IllegalStateException("failure"));
        CommandResult<Void> redacted = ExceptionDetailRedactor.redact(exception, false);
        QueryResult<List<String>> query = QueryResult.success(
            correlationId,
            List.of("one"),
            new PagingInfo(0, 10, 1),
            new ChangeSet<>(List.of("one"), List.of(), List.of()));
        QueryResult<String> single = QueryResult.success(correlationId, "one");
        QueryResult<String> pending = QueryResult.notReady(correlationId);
        QueryResult<String> rejected = QueryResult.unauthorized(correlationId);
        QueryResult<String> queryException = QueryResult.exception(
            correlationId,
            new IllegalStateException("failure"));
        QueryResult<String> redactedQuery = ExceptionDetailRedactor.redact(queryException, false);

        Object[] getters = {
            success.getCorrelationId(),
            response.getResponse(),
            invalid.getValidationResults(),
            malformed.getExceptionStackTrace(),
            unauthorized.getAuthorizationFailureReason(),
            redacted.getExceptionMessages(),
            query.getData(),
            single.getData(),
            pending.isReady(),
            rejected.isAuthorized(),
            redactedQuery.getExceptionMessages()
        };
        if (getters.length == 0) {
            throw new IllegalStateException("Unreachable");
        }
        return ArcHttpStatusMapper.map(invalid);
    }

    /** Exercises explicit context, registry, pipeline, and CompletionStage APIs from Java. */
    public static CompletionStage<? extends CommandResult<?>> commandExecution(
        CreateOrder command,
        CommandHandler handler,
        ServiceResolver serviceResolver,
        JavaAsyncScope asyncScope,
        UUID correlationId) {
        CommandHandlerRegistry registry = new ConcurrentCommandHandlerRegistry();
        registry.register(handler);
        ArcPrincipal principal = new ArcPrincipal("Ada", true, Set.of("operator"));
        CommandExecutionOptions options = new CommandExecutionOptions(
            correlationId,
            principal,
            serviceResolver,
            "tenant-one",
            "tenant-one-namespace",
            ValidationResultSeverity.Warning,
            false);
        CommandContext context = new CommandContext(
            correlationId,
            command,
            CreateOrder.class,
            principal,
            "tenant-one",
            "tenant-one-namespace",
            ValidationResultSeverity.Warning,
            serviceResolver,
            false,
            null);
        if (registry.find(CreateOrder.class) != handler
            || registry.snapshot().isEmpty()
            || context.getCommandType() != CreateOrder.class
            || context.getServiceResolver().resolve(Object.class) == context) {
            throw new IllegalStateException("Public command execution API contract changed");
        }
        DefaultCommandPipeline pipeline = new DefaultCommandPipeline(registry);
        return asyncScope.commands(pipeline).execute(command, options);
    }

    /** Exercises the Java nested-execution factory and opaque token getter. */
    public static CommandExecutionOptions nestedCommandExecution(CommandContext parentContext) {
        CommandExecutionToken token = parentContext.getExecutionToken();
        CommandExecutionOptions nested = CommandExecutionOptions.nested(parentContext);
        if (token == null) {
            throw new IllegalArgumentException("A pipeline context is required");
        }
        return nested;
    }

    /** Exercises query request, context, registry, pipeline, and CompletionStage APIs from Java. */
    public static CompletionStage<? extends QueryResult<?>> queryExecution(
        QueryPerformer performer,
        ServiceResolver serviceResolver,
        JavaAsyncScope asyncScope,
        UUID correlationId) {
        FullyQualifiedQueryName name = new FullyQualifiedQueryName("io.cratis.Orders.all");
        QueryRequest request = new QueryRequest(
            name,
            Map.of("state", "open"),
            new QueryPaging(2, 25),
            new QuerySorting("createdAt", QuerySortDirection.DESCENDING));
        QueryPerformerRegistry registry = new ConcurrentQueryPerformerRegistry();
        registry.register(performer);
        ArcPrincipal principal = new ArcPrincipal("Ada", true, Set.of("operator"));
        QueryExecutionOptions options = new QueryExecutionOptions(
            correlationId,
            principal,
            serviceResolver,
            "tenant-one",
            "tenant-one-namespace",
            ValidationResultSeverity.Warning,
            false);
        QueryContext context = new QueryContext(
            correlationId,
            request,
            name,
            principal,
            "tenant-one",
            "tenant-one-namespace",
            serviceResolver,
            ValidationResultSeverity.Warning,
            false);
        if (registry.find(name) != performer
            || registry.snapshot().isEmpty()
            || context.getRequest().getPaging().getPageSize() != 25
            || context.getServiceResolver().resolve(Object.class) == context) {
            throw new IllegalStateException("Public query execution API contract changed");
        }
        DefaultQueryPipeline pipeline = new DefaultQueryPipeline(registry);
        return asyncScope.queries(pipeline).perform(request, options);
    }

    /** Exercises Java-friendly identity contracts and the asynchronous provider adapter. */
    public static CompletionStage<IdentityProviderResult<UserDetails>> identity() {
        IdentityProviderContext context = new IdentityProviderContext(
            "user-42",
            "Ada",
            List.of(new IdentityClaim("sub", "user-42")));
        AsyncIdentityDetailsProvider<UserDetails> asyncProvider = new AsyncIdentityDetailsProvider<>() {
            @Override
            public Class<UserDetails> getDetailsType() {
                return UserDetails.class;
            }

            @Override
            public CompletionStage<IdentityDetails<UserDetails>> provide(IdentityProviderContext providerContext) {
                return CompletableFuture.completedFuture(
                    new IdentityDetails<>(true, new UserDetails(providerContext.getName())));
            }
        };
        IdentityDetailsProvider<UserDetails> provider = new AsyncIdentityDetailsProviderAdapter<>(asyncProvider);
        if (provider.getDetailsType() != UserDetails.class
            || !".cratis-identity".equals(IdentityConstants.IDENTITY_COOKIE_NAME)) {
            throw new IllegalStateException("Public identity API contract changed");
        }
        return asyncProvider.provide(context).thenApply(details -> new IdentityProviderResult<>(
            context.getId(),
            context.getName(),
            true,
            details.isUserAuthorized(),
            List.of("operator"),
            details.getDetails()));
    }

    /** Exercises host-neutral tenancy and development-provider contracts from Java. */
    public static CompletionStage<List<?>> tenancy(CoroutineScope coroutineScope) {
        ArcPrincipal principal = new ArcPrincipal(
            "Ada",
            true,
            Set.of("operator"),
            "user-42",
            List.of(new IdentityClaim("tenant_id", "claim-tenant")));
        TenantResolutionContext context = new TenantResolutionContext(
            Map.of("X-Cratis-Tenant-Id", "header-tenant"),
            Map.of("tenantId", "query-tenant"),
            "subdomain.example.com",
            List.of(),
            principal);
        TenancyOptions options = new TenancyOptions();
        CompositeTenantIdResolver resolver = new CompositeTenantIdResolver(List.of(
            new QueryStringTenantIdResolver(options),
            new HeaderTenantIdResolver(options),
            new ClaimTenantIdResolver(options)));
        TenantId resolved = resolver.resolve(context);
        TenantId subdomain = new SubdomainTenantIdResolver(
            new TenancyOptions(
                TenancyOptions.DEFAULT_HEADER_NAME,
                TenancyOptions.DEFAULT_QUERY_PARAMETER_NAME,
                TenancyOptions.DEFAULT_CLAIM_TYPE,
                "example.com",
                TenantId.DEVELOPMENT))
            .resolve(context);
        if (!new TenantId("query-tenant").equals(resolved)
            || !new TenantId("subdomain").equals(subdomain)
            || !TenantId.DEFAULT.isDefault()) {
            throw new IllegalStateException("Public tenancy API contract changed");
        }

        AsyncUsersProvider asyncUsers = () -> CompletableFuture.completedFuture(List.of(
            new User(principal, Map.of("displayName", "Ada"))));
        AsyncTenantsProvider asyncTenants = () -> CompletableFuture.completedFuture(List.of(
            new Tenant(new TenantId("tenant-one"), new TenantName("Tenant One"))));
        UsersProviderAggregator users = new UsersProviderAggregator(List.of(new AsyncUsersProviderAdapter(asyncUsers)));
        TenantsProviderAggregator tenants = new TenantsProviderAggregator(
            List.of(new AsyncTenantsProviderAdapter(asyncTenants)));
        return users.provideAsync(coroutineScope).thenCombine(
            tenants.provideAsync(coroutineScope),
            (providedUsers, providedTenants) -> List.of(providedUsers, providedTenants));
    }

    /** Exercises registry and mapper APIs from Java. */
    public static Animal mapper() throws Exception {
        DerivedTypeRegistry registry = new ConcurrentDerivedTypeRegistry();
        registry.register(Animal.class, Cat.class);
        ObjectMapper mapper = ArcObjectMapper.create(registry);
        ArcObjectMapper.configure(mapper, registry);
        String json = mapper.writeValueAsString(new Cat("Milo"));
        Animal animal = mapper.readValue(json, Animal.class);
        String converted = ArcCamelCase.convert("OrderId");
        if (!"orderId".equals(converted) || !"cat".equals(registry.idFor(Animal.class, Cat.class))) {
            throw new IllegalStateException("Public API contract changed");
        }
        return animal;
    }

    /** Typed Java identity details model. */
    public record UserDetails(String displayName) {
    }

    /** Java record implementing a single-value concept without boilerplate. */
    public record OrderId(UUID value) implements ConceptAs<UUID> {
    }

    /** Ordinary enums use their ordinals on the wire. */
    public enum OrdinaryState {
        Pending,
        Complete
    }

    /** Arc enums can supply explicit wire values. */
    public enum ExplicitState implements ArcEnum {
        Pending(10),
        Complete(20);

        private final int wireValue;

        ExplicitState(int wireValue) {
            this.wireValue = wireValue;
        }

        @Override
        public int value() {
            return wireValue;
        }
    }

    /** Base type for explicit polymorphic registration. */
    public interface Animal {
    }

    /** Java derived record. */
    @DerivedType(id = "cat")
    public record Cat(String name) implements Animal {
    }

    /** Command annotation and repeatable authorization annotations from Java. */
    @Command
    @Authorize(policy = "orders", roles = {"operator"}, schemes = {"bearer"})
    @Roles("admin")
    @Roles("operator")
    @TreatWarningsAsErrors
    public record CreateOrder(OrderId id) {
        /** Annotated record accessor. */
        @CommandKey
        public OrderId id() {
            return id;
        }

        /** Annotated command operation. */
        @TreatWarningsAsErrors
        public CommandResult<OrderId> handle(@FromServices Object service) {
            return CommandResult.success(UUID.randomUUID(), id);
        }
    }

    /** Read model and query annotations from Java. */
    @ReadModel
    public record OrderView(OrderId id, OrdinaryState state, ExplicitState explicitState) {
    }

    /** Query container exercising path, transport, and anonymous access annotations. */
    public static final class Orders {
        private Orders() {
        }

        /** Annotated query operation. */
        @AllowAnonymous
        @Path("/orders/{id}")
        @QueryHttpMethod(QueryHttpMethodType.GET)
        @QueryTransport(QueryTransportType.OBSERVABLE)
        public static List<OrderView> byId(OrderId id) {
            return List.of();
        }
    }
}
