---
title: Build your first Arc application with Java
description: Create and run a minimal Java Spring Boot command and query using records and CompletionStage.
---

## Prerequisites

Use JDK 17 and Gradle 8.14.4. This tutorial follows the passing `Samples/Java/SpringBoot` application. The local workspace version is `0.0.0-SNAPSHOT`; substitute a released version when consuming published artifacts.

Arc generates Kotlin implementations for Java models, so the build also uses Kotlin 2.4.10 and KSP 2.3.11 (KSP2). The Arc plugin supplies this tooling. See the [compiler compatibility notes](index.md#compiler-compatibility), including the Gradle 8.14.4 requirement, when configuring the plugins manually or mixing Kotlin and Java sources.

## Configure Gradle

The Arc plugin marker is configured for publication through Maven Central. Add Maven Central to plugin resolution in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Create `build.gradle.kts` using the Arc plugin and Spring Boot starter. Include application dependency repositories here: `pluginManagement.repositories` only resolves plugins, not application or processor dependencies.

```kotlin
plugins {
    java
    id("io.cratis.arc") version "<version>"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

repositories {
    mavenCentral()
}

cratisArc {
    moduleName.set("TaskApplication")
    dependencyVersion.set("<version>")
    endpoints {
        segmentsToSkip.set(2)
    }
}

dependencies {
    implementation("io.cratis:arc-spring-boot-starter:<version>")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
```

For builds that do not use the Arc plugin, retain `repositories { mavenCentral() }` in `build.gradle.kts`, apply `java`, Kotlin/JVM `2.4.10`, KSP `2.3.11`, and Spring Boot directly; add `io.cratis:arc`, `io.cratis:arc-spring-boot-starter`, and `ksp("io.cratis:arc-ksp:<version>")`, then set `ksp { arg("arc.moduleName", "TaskApplication") }`. The plugin and manual setup produce the same generated contracts.

Set the matching host convention in `src/main/resources/application.properties`:

```properties
cratis.arc.endpoints.segments-to-skip-for-route=2
```

## Add the application model

Create each public type in its named Java file under `src/main/java/example/tasks/`. The command and query return `CompletionStage`; generated Arc adapters await them without reflection.

`TaskCreated.java`:

```java
package example.tasks;

public record TaskCreated(String id, String title) {}
```

`TaskRepository.java`:

```java
package example.tasks;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public final class TaskRepository {
    private final ConcurrentHashMap<String, TaskView> tasks = new ConcurrentHashMap<>();

    public TaskView create(String title) {
        var task = new TaskView(UUID.randomUUID().toString(), title.trim());
        tasks.put(task.id(), task);
        return task;
    }

    public List<TaskView> all() {
        return tasks.values().stream().sorted(Comparator.comparing(TaskView::title)).toList();
    }
}
```

`CreateTask.java`:

```java
package example.tasks;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Command
@AllowAnonymous
public record CreateTask(String title) {
    public CompletionStage<TaskCreated> handle(TaskRepository repository) {
        var task = repository.create(title);
        return CompletableFuture.completedFuture(new TaskCreated(task.id(), task.title()));
    }
}
```

`TaskView.java`:

```java
package example.tasks;

import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.queries.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@ReadModel
@AllowAnonymous
public record TaskView(String id, String title) {
    @Path("/api/tasks")
    public static CompletionStage<TaskView[]> all(@FromServices TaskRepository repository) {
        return CompletableFuture.completedFuture(repository.all().toArray(TaskView[]::new));
    }
}
```

Create `src/main/java/example/TaskApplication.java` so Spring scans the `example.tasks` package:

```java
package example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TaskApplication {
    public static void main(String[] args) {
        SpringApplication.run(TaskApplication.class, args);
    }
}
```

Run the application:

```bash
./gradlew bootRun
```

## Execute and query

```bash
curl -sS -X POST http://localhost:8080/api/create-task \
  -H 'Content-Type: application/json' \
  -d '{"title":"Try Arc"}'

curl -sS -X QUERY http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{}}'
```

The command returns `isSuccess: true` with a typed `response`. Identifiers change on every run:

```json
{"correlationId":"<uuid>","isAuthorized":true,"validationResults":[],"exceptionMessages":[],"exceptionStackTrace":"","authorizationFailureReason":"","isValid":true,"hasExceptions":false,"isSuccess":true,"response":{"id":"<task-id>","title":"Try Arc"}}
```

The query returns `isSuccess: true` with the created task array in `data`:

```json
{"correlationId":"<uuid>","data":[{"id":"<task-id>","title":"Try Arc"}],"isReady":true,"isAuthorized":true,"validationResults":[],"exceptionMessages":[],"exceptionStackTrace":"","paging":{"page":0,"size":0,"totalItems":0,"totalPages":0},"isValid":true,"hasExceptions":false,"isSuccess":true}
```

The current array-returning adapter leaves paging totals at zero; the Kotlin tutorial's `List` return reports `totalItems: 1`. Neither example requests paging. See the [HTTP contract reference](../reference/http-contract.md) for the envelope contract.

The [executable tutorial check](index.md#query-the-read-model) compiles these Java files and generated Kotlin adapters with warnings as errors, then verifies the documented requests against a real Spring Boot host. It tests the preferred plugin setup using locally staged Arc publications, not every documentation snippet or the manual setup.

## Call pipelines from an imperative Java service

Inject `io.cratis.arc.java.BlockingCommandPipeline` and `BlockingQueryPipeline` for an ordinary
imperative service or scheduled job. The Spring starter supplies both beans and backs off when you
supply your own. Unlike the `Blocking*Handler` adapters (which implement pipeline extension points),
these facades let the **caller** execute, validate, and perform one-shot queries without a
`Continuation`, `CompletionStage.join()`, or an owned executor.

Supply `CommandExecutionOptions` or `QueryExecutionOptions` on every call. Capture the intended
principal, tenant, correlation ID and `ServiceResolver` explicitly at entry; the facade neither
reads request/security thread locals nor creates an anonymous identity. Authorization and validation
still run through the configured pipelines. For example, a constructor-injected service can expose:

```java
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.java.BlockingCommandPipeline;
import io.cratis.arc.java.BlockingQueryPipeline;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.QueryResult;
import org.springframework.stereotype.Service;

@Service
public final class TaskOperations {
    private final BlockingCommandPipeline commands;
    private final BlockingQueryPipeline queries;

    public TaskOperations(BlockingCommandPipeline commands, BlockingQueryPipeline queries) {
        this.commands = commands;
        this.queries = queries;
    }

    public CommandResult<?> execute(Object command, CommandExecutionOptions options) {
        return commands.execute(command, options);
    }

    public CommandResult<?> validate(Object command, CommandExecutionOptions options) {
        return commands.validate(command, options); // Does not invoke the command handler.
    }

    public QueryResult<?> perform(QueryRequest request, QueryExecutionOptions options) {
        return queries.perform(request, options);
    }
}
```

A scheduler has no incoming request: choose a configured application identity and tenant, and create
fresh correlation/options for each scheduled invocation. Do not invent an authenticated principal
from untrusted request data. A Java service with constructor injection and an explicitly invoked
`@Scheduled` method is compiled and executed by
`Integrations/SpringBoot`'s `BlockingPipelineJavaConformanceTest`; this does not test scheduler timing.
`Source`'s `BlockingPipelineJavaConformanceTest` verifies context capture, authorization, validation
nonexecution, results, failed/cancelled stages, and interrupted command cleanup.

For deliberately bound context, construct `new BlockingCommandPipeline(pipeline, options)` to use
`execute(command)` and `validate(command)`, or `new BlockingQueryPipeline(pipeline, options)` to use
`perform(request)`. Binding retains those exact options, including correlation and identity; it does
not refresh or resolve them at runtime. Prefer a short-lived bound facade when context is per-call,
not a singleton holding request-scoped data. An explicit per-call options argument overrides the
binding. The default Spring beans are **unbound**: short calls throw `IllegalStateException` before
execution rather than silently choosing security context.

### Blocking, interruption, and limits

- Each call uses `runBlocking` on the caller thread and occupies that thread until completion. No
  application scope or executor is used for offloading. This is an opt-in imperative entry point,
  including conventional MVC service calls or scheduler workers; generated Arc HTTP endpoints keep
  their existing coroutine hosting. Size caller capacity accordingly.
- Do not call these facades from coroutines, event loops, Arc handlers, or Arc bounded application
  work. Use `CommandPipeline`/`QueryPipeline` from Kotlin, or the existing async facades from Java.
  Marked Arc bounded work and reentrant blocking-facade work fail fast with `IllegalStateException`,
  including after `withContext` dispatcher migration. The safety marker restores pooled-thread
  state; it is not request context. It cannot detect every unrelated external coroutine or work
  detached into a fresh context. There are no thread-name heuristics.
- Returned `CommandResult<?>` and `QueryResult<?>` are unchanged: inspect `isSuccess()` and the
  validation/authorization/exception fields. A failed result is not automatically thrown. Exceptions
  propagated by the underlying pipeline and ordinary handler cancellation retain their exact failure
  identity rather than being wrapped by the facade, except for the interruption policy below.
- Interrupting a blocked caller cancels the invocation, waits for cooperative structured cleanup,
  restores the caller's interrupt flag, then throws unchecked `CancellationException` with
  `InterruptedException` as its cause. This includes synchronous handlers, performers, and validators
  whose interruptible waits throw before the default pipelines could turn them into failure results.
  Begun command scopes complete in reverse order with a failed result; interrupted validation stops
  before subsequent validators or the handler/performer. An already-interrupted caller does not
  start execution. Repeated interrupts do not detach cleanup.
- **Normalization policy:** any directly observed `InterruptedException` at framework-managed
  callback boundaries during this blocking invocation means cancellation, including an exception
  propagated from a worker through `withContext`. The facade sets the **caller** interrupt flag even
  in that worker-originated case; this is not evidence that the caller was physically interrupted.
  The cause is the exception observed at the framework boundary (coroutine stack recovery may already
  have copied an upstream exception). Shared model/concept traversal also recognizes interruption
  through its existing reflection/stage wrappers; there is no arbitrary cause-chain search.
  Application-swallowed interruption is undetectable, including replacement pipelines that consume
  it themselves. The first framework-observed interruption also takes precedence over an earlier
  ordinary cancellation retained during command-scope cleanup; every begun scope still completes.
  This policy is not enabled for ordinary suspending/async pipeline callers or by the bounded-scope
  safety marker alone. Ordinary handler cancellation without interruption does not set the flag.
- Existing `CompletionStage.await` cancels the stage when it also implements `Future`; cancellation
  cannot force arbitrary external work to stop or undo a side effect. `Source`'s
  `BlockingRealPipelineInterruptionTest` covers real synchronous and staged command/query execution,
  command/query/model validation, cleanup/rollback, worker normalization, and unchanged legacy and
  guard-only coroutine behavior. `BlockingPipelineNormalizationTest` covers callback exception sites,
  completion-time interruption, reflective traversal, and invocation-marker restoration.
- There is no facade timeout. The application owns its deadline and interrupting caller-task policy;
  a caller timeout is not a promise that cleanup or external work has already stopped. Cleanup must
  cooperate, so an uncooperative handler or cleanup can keep the caller blocked. Existing pipeline
  scope-completion timeouts still apply. Use the async API when retaining a blocked thread is wrong.

Continue with [in-process Java testing](../guides/testing.md) or compare the [Kotlin tutorial](index.md).
