---
title: Development users and tenants
description: Implement TenantsProvider and UsersProvider so development tools such as Lens can discover and switch between tenants and users on a running Arc application.
---

## Why this exists

`GET /.cratis/tenants` and `GET /.cratis/users` are development-only discovery
endpoints. They have nothing to do with [tenant resolution](ambient-tenancy.mdx)
at request time — they exist purely so a tool can ask your running application
"which tenants and users do you know about right now?", sourced from wherever
your application already keeps that list, instead of a hand-maintained copy
pasted into the tool.

The primary consumer is [Lens](/tools/lens/), the Cratis
browser extension for exercising a running Arc application during development.
Lens reads these two endpoints to populate its tenant and user pickers, then
injects the corresponding tenant and identity headers into every request the
inspected page makes while a selection is active — no restart, no
hand-crafted headers. See [Lens: where the tenant and user roster comes from](/tools/lens/#where-the-tenant-and-user-roster-comes-from)
for the extension side of this contract, with screenshots against seeded demo
data. Both endpoints are explicitly
anonymous — see [Know which built-in routes are anonymous](security.mdx#know-which-built-in-routes-are-anonymous)
— including in production, so scope development-only providers out of
production builds or restrict the paths at trusted ingress.

Without any provider registered, both endpoints return an empty JSON array
rather than failing.

## Implement a tenants provider

Register a `TenantsProvider` bean. It is a Kotlin `fun interface`, so a lambda
is enough:

```kotlin
import io.cratis.arc.tenancy.Tenant
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.TenantName
import io.cratis.arc.tenancy.TenantsProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DevelopmentTenants {
    @Bean
    fun developmentTenantsProvider() = TenantsProvider {
        listOf(
            Tenant(TenantId.of("acme-corp"), TenantName("ACME Corporation")),
            Tenant(TenantId.of("widget-inc"), TenantName("Widget Inc"))
        )
    }
}
```

`provide()` is a suspend function, so it can call a repository or a remote
service directly. `GET /.cratis/tenants` returns each `Tenant` as
`{"id": "...", "name": "..."}`.

Java registers an `AsyncTenantsProvider` instead, returning a
`CompletionStage<List<Tenant>>`:

```java
import io.cratis.arc.tenancy.AsyncTenantsProvider;
import io.cratis.arc.tenancy.Tenant;
import io.cratis.arc.tenancy.TenantId;
import io.cratis.arc.tenancy.TenantName;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DevelopmentTenants {
    @Bean
    public AsyncTenantsProvider developmentTenantsProvider() {
        return () -> CompletableFuture.completedFuture(List.of(
            new Tenant(TenantId.of("acme-corp"), new TenantName("ACME Corporation")),
            new Tenant(TenantId.of("widget-inc"), new TenantName("Widget Inc"))
        ));
    }
}
```

## Implement a users provider

`UsersProvider` (Kotlin) and `AsyncUsersProvider` (Java) follow the same
shape and return `User`, which pairs a host-neutral `ArcPrincipal` with
optional application-specific `details`:

```kotlin
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.User
import io.cratis.arc.identity.UsersProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DevelopmentUsers {
    @Bean
    fun developmentUsersProvider() = UsersProvider {
        listOf(
            User(
                ArcPrincipal(
                    name = "alice@contoso.com",
                    isAuthenticated = true,
                    roles = setOf("admin", "developer"),
                    authenticationScheme = "aad"
                ),
                mapOf("department" to "Engineering")
            ),
            User(
                ArcPrincipal(
                    name = "bob@contoso.com",
                    isAuthenticated = true,
                    roles = setOf("tester"),
                    authenticationScheme = "aad"
                ),
                mapOf("department" to "QA")
            )
        )
    }
}
```

`GET /.cratis/users` returns each entry as
`{"principal": {"id": "...", ...}, "details": {...}}`.

## Multiple providers compose

Register more than one `TenantsProvider` or `UsersProvider` — Arc aggregates
every registered coroutine and Java async provider in `@Order` sequence and
keeps the first entry seen for each tenant or user identifier, so one source
can supply fixtures while another reads from a database or an external
service without either implementation knowing about the other.

## See also

- [Lens](/tools/lens/) — the browser extension this page's providers feed, with screenshots against seeded demo data.
- [C#: implementing a tenants provider](/arc/backend/csharp/identity/development-and-topologies/#implementing-a-tenants-provider) — the same discovery contract on ASP.NET Core.
- [Tenancy](ambient-tenancy.mdx) — request-time tenant resolution, which this page's providers are deliberately separate from.
- [Authenticate and authorize Arc endpoints](security.mdx) — the anonymous-route list these endpoints belong to, and `GET /.cratis/me`.
