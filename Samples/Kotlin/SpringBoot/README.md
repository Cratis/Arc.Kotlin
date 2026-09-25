# Kotlin Spring Boot sample

The Arc showcase written in Kotlin, plus a task board. It has no controllers, no Chronicle
dependency, and needs no external infrastructure to start.

## Run it

```shell
./run.sh                      # in memory, with the frontend on :5173
./run.sh --database mongodb   # store the task board in MongoDB (Docker)
./run.sh --database postgres  # store the task board in PostgreSQL (Docker)
./run.sh --no-frontend        # backend only, for the curl requests below
./run.sh --sign-in-required   # start signed out, so the anonymous paths are reachable
```

`../../run.sh --help` lists every option. Open the frontend rather than the backend: it proxies
`/api` and `/.cratis` through, so the browser sees one origin.

## What to read

| Concern | Where |
| --- | --- |
| A command, its validation, and a typed response | `CreateTask.kt`, `CreateTaskValidator.kt` |
| `provide` loading state before `handle` runs | `CompleteTask.kt` |
| One-shot and observable queries on one read model | `TaskView.kt` |
| Swapping where state lives without touching an artifact | `TaskRepository.kt`, `persistence/` |
| Identity captured once, at transport entry | `SampleAuthentication.kt` |
| Cross-cutting rules for a whole feature | `features/crosscuttingauthorization/` |
| Every showcase feature | `features/` |

The showcase features under `features/` mirror the Arc .NET sample application one for one; the
[samples overview](../../README.md) lists what each page demonstrates.

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

# An observable query answers a snapshot GET from the source's current value.
curl -sS http://localhost:8080/api/features/ticker/observe

# The identity the sample resolved.
curl -sS http://localhost:8080/.cratis/me
```

## Storage

`--database` changes only which `TaskRepository` bean is active. The commands, the queries and the
read model are untouched:

| Value | Implementation |
| --- | --- |
| `memory` | `InMemoryTaskRepository` — bounded, no setup. The default. |
| `mongodb` | `persistence/MongoTaskRepository.kt`, Spring Data MongoDB with `@Version` concurrency. |
| `postgres` | `persistence/JpaTaskRepository.kt`, Spring Data JPA with `@Version` concurrency. |

Arc routes MongoDB through a tenant-certified `TenantMongoOperations`, so the database is chosen by
the resolved tenant rather than by the path in the connection string — with no tenant header, look
in the driver's default database rather than in `arc-samples`.

## Generate the TypeScript proxies on their own

Run this from the repository root, which holds the Gradle wrapper:

```shell
./gradlew :Samples:Kotlin:SpringBoot:generateArcProxies --no-configuration-cache
```

Output lands untracked in `build/generated/arc-proxies`, laid out by feature. `./run.sh` does this
for you and copies the result into the frontend before Vite starts, so the client can never be a
build behind the server.
