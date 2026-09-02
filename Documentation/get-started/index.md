---
title: Build your first Arc application with Kotlin
description: Create and run a minimal Kotlin Spring Boot command and query with Arc's generated, model-bound endpoints.
---

## Prerequisites

Use JDK 17 and Gradle 8.13. This tutorial follows the passing `Samples/Kotlin/SpringBoot` application in the repository. The local workspace version is `0.0.0-SNAPSHOT`; substitute a released version when consuming published artifacts.

For Java records and `CompletionStage`, follow [Build your first Arc application with Java](java.md).

## Configure Gradle

The Arc plugin marker is published through Maven Central. Add Maven Central to plugin resolution in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

The preferred setup is the Arc plugin. It applies Kotlin/JVM and KSP, adds `io.cratis:arc` and `io.cratis:arc-ksp`, targets JDK 17, and treats warnings as errors.

```kotlin
plugins {
    id("io.cratis.arc") version "<version>"
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
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
    implementation("org.springframework.boot:spring-boot-starter-web")
}
```

If the plugin is not available in your build, use manual KSP setup:

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    id("com.google.devtools.ksp") version "2.1.0-1.0.29"
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

dependencies {
    implementation("io.cratis:arc:<version>")
    implementation("io.cratis:arc-spring-boot-starter:<version>")
    implementation("org.springframework.boot:spring-boot-starter-web")
    ksp("io.cratis:arc-ksp:<version>")
}

ksp {
    arg("arc.moduleName", "TaskApplication")
}
```

Set the matching host route convention in `src/main/resources/application.properties`. The example package has two segments, so skipping both produces `/api/create-task`:

```properties
cratis.arc.endpoints.segments-to-skip-for-route=2
```

## Add the application model

Create a Spring repository, a command, and a read model. Arc discovers the model and resolves parameters from Spring. `@AllowAnonymous` makes the tutorial endpoints callable without authentication.

```kotlin
package example.tasks

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Repository

@Repository
class TaskRepository {
    private val tasks = ConcurrentHashMap<String, TaskView>()
    fun create(title: String): TaskView =
        TaskView(UUID.randomUUID().toString(), title.trim()).also { tasks[it.id] = it }
    fun all(): List<TaskView> = tasks.values.sortedBy(TaskView::title)
}

data class TaskCreated(val id: String, val title: String)

@Command
@AllowAnonymous
data class CreateTask(val title: String) {
    fun handle(repository: TaskRepository): TaskCreated {
        val task = repository.create(title)
        return TaskCreated(task.id, task.title)
    }
}

@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        @Path("/api/tasks")
        fun all(@FromServices repository: TaskRepository): List<TaskView> = repository.all()
    }
}
```

Create `src/main/kotlin/example/TaskApplication.kt` so Spring scans the `example.tasks` package:

```kotlin
package example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TaskApplication

fun main(args: Array<String>) {
    runApplication<TaskApplication>(*args)
}
```

Run the application:

```bash
./gradlew bootRun
```

## Execute the command

```bash
curl -sS -X POST http://localhost:8080/api/create-task \
  -H 'Content-Type: application/json' \
  -d '{"title":"Try Arc"}'
```

The identifier changes on every run. The response has this shape:

```json
{"correlationId":"<uuid>","isAuthorized":true,"validationResults":[],"exceptionMessages":[],"exceptionStackTrace":"","authorizationFailureReason":"","isValid":true,"hasExceptions":false,"isSuccess":true,"response":{"id":"<task-id>","title":"Try Arc"}}
```

## Query the read model

```bash
curl -sS -X QUERY http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{}}'
```

The one-shot query returns the created task in a `QueryResult` envelope:

```json
{"correlationId":"<uuid>","data":[{"id":"<task-id>","title":"Try Arc"}],"isReady":true,"isAuthorized":true,"validationResults":[],"exceptionMessages":[],"exceptionStackTrace":"","paging":{"page":0,"size":0,"totalItems":0,"totalPages":0},"isValid":true,"hasExceptions":false,"isSuccess":true}
```

Continue with the [commands guide](../guides/commands.md) and [queries guide](../guides/queries.md).
