---
title: Authenticate and authorize Arc endpoints
description: Register Kotlin and Java authentication handlers, declare endpoint authorization, and expose application identity details safely.
---

## Register a Kotlin authentication handler

Arc authenticates requests through ordered `AuthenticationHandler` beans before protected command, query, identity, and diagnostics endpoints run. A handler receives an immutable `AuthenticationRequestContext` containing case-insensitive headers, cookies, the principal captured by the host, and the selected tenant when one exists.

Return `AuthenticationResult.ANONYMOUS` when the handler does not recognize the request. Return `failed` when it recognizes credentials but rejects them, or `succeeded` with an `ArcPrincipal` when it accepts them:

```kotlin
@Bean
@Order(10)
fun bearerAuthentication(): AuthenticationHandler = AuthenticationHandler { context ->
    when (context.header("Authorization")) {
        null -> AuthenticationResult.ANONYMOUS
        "Bearer valid-token" -> AuthenticationResult.succeeded(
            ArcPrincipal(
                name = "Ada",
                isAuthenticated = true,
                roles = setOf("admin"),
                id = "user-42",
                authenticationScheme = "Bearer"
            )
        )
        else -> AuthenticationResult.failed(AuthenticationFailureReason.of("invalid-token"))
    }
}
```

A success or failure is terminal: no later handler runs. A failed handler can therefore never be overridden by a later success. Anonymous is not a rejection and lets the chain continue; it becomes the final result only when no handler recognized the request. The HTTP response exposes failures only as a generic 401 and never returns the handler's private failure reason.

Kotlin `AuthenticationHandler` beans execute in Spring order, followed by Java `AsyncAuthenticationHandler` beans in Spring order. When ordering across both implementation styles must be global, register the Java handler through `AsyncAuthenticationHandlerAdapter` as an `AuthenticationHandler` bean.

## Register a Java asynchronous handler

Java implements `AsyncAuthenticationHandler` with `CompletionStage`, without coroutine types:

```java
@Bean
@Order(20)
AsyncAuthenticationHandler apiKeyAuthentication() {
    return context -> {
        String value = context.header("X-Api-Key");
        AuthenticationResult result;
        if (value == null) {
            result = AuthenticationResult.ANONYMOUS;
        } else if (value.equals("valid-key")) {
            result = AuthenticationResult.succeeded(
                new ArcPrincipal("Ada", true, Set.of("operator"), "user-42", List.of(), "ApiKey"));
        } else {
            result = AuthenticationResult.failed(AuthenticationFailureReason.of("invalid-api-key"));
        }
        return CompletableFuture.completedFuture(result);
    };
}
```

Register the async handler itself as a bean; Arc adapts it to the coroutine-first chain and propagates cancellation. Do not block the request thread while verifying credentials.

## Declare authorization on artifacts and operations

Arc generates authorization metadata from `@Authorize`, `@Roles`, and `@AllowAnonymous` on a command or read-model class and its operation (`handle` or the query function).

```kotlin
@Authorize(policy = "activeSubscription")
@Roles("member")
@Command
class UpdateProfile {
    fun handle(): Unit = Unit
}
```

An authorization decision with several dimensions requires all declared dimensions:

- The caller must be authenticated unless `@AllowAnonymous` applies.
- A named policy must return `AuthorizationResult.success()`.
- The caller must hold at least one role from the combined role list.
- The captured authentication scheme must match at least one declared scheme, ignoring case.

Register named policies as Spring beans. The bean name is the policy name used by `@Authorize`:

```kotlin
@Bean("activeSubscription")
fun activeSubscription(): AuthorizationPolicy = AuthorizationPolicy { principal ->
    if (principal.claims.any { it.type == "subscription" && it.value == "active" }) {
        AuthorizationResult.success()
    } else {
        AuthorizationResult.failure("An active subscription is required.")
    }
}
```

Java policies use `BlockingAuthorizationPolicyAdapter` or `AsyncAuthorizationPolicyAdapter`, so Java implementations never implement a suspending method.

## Understand class and operation precedence

The operation wins when it declares any `@Authorize` or `@Roles`: only its policy, roles, and schemes apply, and class authorization is ignored. Otherwise, the class declaration applies. An operation can therefore narrow access without accidentally retaining roles from its class.

Repeated `@Roles` declarations on the same target combine, and holding any one listed role satisfies the role check. Policy, role, and scheme checks are still cumulative.

Do not combine `@AllowAnonymous` with `@Authorize` or `@Roles`, either on the same target or across a class and operation. KSP reports `ARCKSP0108` and stops generation instead of guessing which security declaration should win.

## Use Spring Security when present

Spring Security remains optional. When it is on the classpath, Arc maps the request's Spring `Authentication` into an `ArcPrincipal`, retaining its name, authenticated state, string-valued authorities, claims, stable identity identifier, and authentication scheme. Authorities prefixed with `ROLE_` become Arc role names without the prefix; authorities without a string representation are ignored.

Without Spring Security, Arc uses the servlet principal. In both cases Arc captures the principal before asynchronous work begins, so command and query pipelines never depend on thread-local security state after suspension.

An application can replace Arc's complete `Authentication` service with its own bean. Supplying that override bypasses the default ordered handler aggregator and is appropriate only when the application owns the entire chain.

## Expose application identity details

Register one `IdentityDetailsProvider<T>` to enable `GET /.cratis/me`. It receives the authenticated principal and returns both the application's authorization decision and its typed identity payload:

```kotlin
data class ApplicationIdentity(val displayName: String)

@Bean
fun identityDetails(): IdentityDetailsProvider<ApplicationIdentity> =
    object : IdentityDetailsProvider<ApplicationIdentity> {
        override val detailsType = ApplicationIdentity::class.java

        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<ApplicationIdentity> =
            IdentityDetails(
                isUserAuthorized = context.claims.any { it.type == "role" && it.value == "member" },
                details = ApplicationIdentity(context.name)
            )
    }
```

Java can register `AsyncIdentityDetailsProvider<T>` through `AsyncIdentityDetailsProviderAdapter`. Arc permits exactly one identity-details provider and fails application startup when several are present.

The identity cache cookie is client-readable by design and is excluded from authentication input, so a client can never authenticate by replaying Arc's cached identity projection.

## Know which built-in routes are anonymous

When no authentication handlers are registered, Arc endpoint behavior is unchanged and callers remain anonymous. Once handlers exist, the following literal metadata routes remain anonymous:

- `GET /.cratis/commands`
- `GET /.cratis/queries`
- `GET /.cratis/users`
- `GET /.cratis/tenants`
- `GET /.cratis/identity-details/schema`

`GET /.cratis/me` requires an authenticated principal because it returns caller-specific identity details.

The fixed multiplexed SSE and WebSocket routes accept an anonymous physical connection, while every subscription still runs its query's authorization pipeline independently. One unauthorized subscription terminates without disturbing authorized subscriptions sharing the connection.

`GET` and `QUERY /.cratis/queries/health` require authentication whenever handlers are registered because the snapshot includes connection identifiers, subscription identifiers, remote addresses, user agents, and user identities. This endpoint is diagnostics, not container health; use Spring Boot Actuator health groups for liveness and readiness.

## Distinguish authentication and authorization failures

Authentication establishes who the caller is and fails with HTTP 401 when credentials are rejected or a protected endpoint receives no authenticated principal. Authorization decides whether that principal may execute a specific command or query and fails with HTTP 403.

Arc returns generic transport failures and keeps private handler or policy details out of the response. Use server-side debug logs, traces, and the stable result envelope when diagnosing a rejection; never return credential or policy internals to the caller.
