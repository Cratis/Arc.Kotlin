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

Continue with [in-process Java testing](../guides/testing.md) or compare the [Kotlin tutorial](index.md).
