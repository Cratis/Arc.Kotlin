# Java Spring Boot sample

The Arc showcase written in ordinary Java, plus a task board. Every signature here is one a Java
developer would write: `CompletionStage` rather than `suspend`, `Flow.Publisher` rather than `Flow`,
and no Kotlin type in the application's own API.

It is the same application as [the Kotlin sample](../../Kotlin/SpringBoot/README.md) and serves the
same routes, which is why the one frontend runs against either unchanged.

## Run it

```shell
./run.sh                      # in memory, with the frontend on :5173
./run.sh --database mongodb   # store the task board in MongoDB (Docker)
./run.sh --database postgres  # store the task board in PostgreSQL (Docker)
./run.sh --no-frontend        # backend only, for the curl requests below
./run.sh --sign-in-required   # start signed out, so the anonymous paths are reachable
```

`../../run.sh --help` lists every option.

## What to read

| Concern | Where |
| --- | --- |
| An asynchronous command returning `CompletionStage` | `CreateTask.java` |
| `provide` loading state before `handle` runs | `CompleteTask.java` |
| One-shot and observable queries without a coroutine type | `TaskView.java` |
| Observable state a snapshot `GET` can read | `features/ticker/TickerSource.java` |
| A hand-written `Flow.Publisher`, for contrast | `TaskRepository.java` |
| Swapping where state lives without touching an artifact | `TaskStore.java`, `persistence/` |
| Blocking filters adapted into the Arc pipeline | `features/crosscuttingauthorization/` |

`TickerSource` and `TaskRepository` are worth reading together. Both publish observable state; one
uses Arc's `ObservableState`, the other hand-rolls a `Flow.Publisher` the way a Java application had
to before that type existed. Only the first can answer a snapshot `GET`, because only it holds a
current value — a plain publisher promises just that values will arrive eventually, so Arc answers
`202 Not Ready` rather than holding the request open.

## Try it with curl

```shell
# Create a task. An empty title returns validation feedback instead of an exception.
curl -sS -X POST http://localhost:8080/api/create-task \
  -H 'Content-Type: application/json' \
  -d '{"title":"Try Arc"}'

# Validate without executing.
curl -sS -X POST http://localhost:8080/api/create-task/validate \
  -H 'Content-Type: application/json' \
  -d '{"title":""}'

# Complete a task. A task changed since preparation returns validation, not a stale write.
curl -sS -X POST http://localhost:8080/api/complete-task \
  -H 'Content-Type: application/json' \
  -d '{"taskId":"<task-id>"}'

# Read the board, by GET and by the RFC QUERY method.
curl -sS 'http://localhost:8080/api/tasks/by-id?id=<task-id>'
curl -sS -X QUERY http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{}}'

# An observable query backed by ObservableState answers from its current value.
curl -sS http://localhost:8080/api/features/ticker/observe

# The identity the sample resolved.
curl -sS http://localhost:8080/.cratis/me
```

## Storage

`--database` changes only which `TaskStore` bean is active. The commands, the queries and the read
model are untouched:

| Value | Implementation |
| --- | --- |
| `memory` | `TaskRepository` — bounded, no setup. The default. |
| `mongodb` | `persistence/MongoTaskStore.java`, Spring Data MongoDB with `@Version` concurrency. |
| `postgres` | `persistence/JpaTaskStore.java`, Spring Data JPA with `@Version` concurrency. |

## Generate the TypeScript proxies on their own

Run this from the repository root, which holds the Gradle wrapper:

```shell
./gradlew :Samples:Java:SpringBoot:generateArcProxies --no-configuration-cache
```

Output lands untracked in `build/generated/arc-proxies`, laid out by feature, with the files the shared frontend
consumes. The Kotlin sample additionally generates calendar and batch-task contract clients.
