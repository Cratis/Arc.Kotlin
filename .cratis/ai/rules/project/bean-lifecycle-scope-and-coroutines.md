---
applyTo: "**/*"
---

## Bean lifecycle, scope, and coroutines

- **Arc work runs in one application-owned scope.** `ArcApplicationCoroutineScope` is registered as
  `@Bean(destroyMethod = "close")` and constructed from `coroutineParallelism` and
  `coroutineQueueCapacity`. It owns a bounded `ThreadPoolExecutor` plus a `Semaphore` admission gate,
  and its `tryLaunch` returns `null` when capacity is exhausted so the host can fail closed. Never
  use `GlobalScope`, `runBlocking` on a request thread, or an unbounded dispatcher.
- **Any bean holding a closable runtime declares `destroyMethod`.** `arcObservableQueryTransport` is
  registered as `@Bean(name = ["arcObservableQueryTransport"], destroyMethod = "close")` for the same
  reason.
- **No `ThreadLocal` for coroutine-visible state, and no request-scoped beans on an Arc path.** A
  coroutine can resume on another thread, so request state is captured *once at transport entry* into
  an explicit immutable object and then passed: `ArcPrincipalFactory` produces an `ArcPrincipal`
  before suspension, and `ArcTenantResolutionService` builds a `TenantResolutionContext` from headers,
  first query-parameter values, host, and claims. Follow that pattern for anything new — do not read
  `SecurityContextHolder` or `RequestContextHolder` after entry.
- **Arc beans are singletons; per-call resolution goes through `ServiceResolver`.**
  `SpringServiceResolver` resolves each generated handler dependency with
  `applicationContext.getBeanProvider(type).ifAvailable`, which is how a prototype- or
  request-scoped application service still works. Do not inject application services directly into
  Arc infrastructure beans.
- **Generated artifacts are discovered once.** `ArcArtifactModules` merges `ServiceLoader`-provided
  and Spring-bean `ArcArtifactModule` instances, deduplicates by concrete class, orders by fully
  qualified class name, and registers them through `ArcArtifactModuleRegistry`. A bean that needs
  artifacts to be registered must depend on `ArcArtifactModules`, not on ordering luck.
- **Fail startup, not the first request, on unrecoverable configuration.** The context deliberately
  fails with messages such as `"Exactly one Arc identity details provider may be registered; found 2"`
  and `"Duplicate Arc POST route '/api/duplicates/same-command'"` that name both offending artifacts.
  Match that quality when adding a startup check.
