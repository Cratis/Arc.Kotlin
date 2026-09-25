<!-- Copyright (c) Cratis. All rights reserved. -->
<!-- Licensed under the MIT license. See LICENSE file in the project root for full license information. -->

# Java Chronicle Spring Boot sample

This optional ordinary-Java sample combines generated Arc endpoints with the Chronicle Spring Boot starter that `io.cratis:arc-chronicle-spring-boot-starter` brings in (Chronicle.Kotlin 6.3.2 by default). It uses `CompletionStage` command/query handlers, the public Arc concurrency builder bridge, and the required `x-cratis-tenant-id` header. The configured `ArcJavaChronicleSample` event store is resolved in exactly that namespace with no default tenant fallback.

## Run it

From this directory, run:

```shell
./run.sh
```

This requires Docker. It starts the pinned `cratis/chronicle:18.4.0-development` kernel, waits for it
to report healthy, runs the application on `:8080` without the shared frontend, and stops the
container again on exit. The sample's event store uses an in-memory sink, so no database is needed.
To use a Chronicle 18.4.0+ kernel you already have running on `localhost:35000`, skip `run.sh` and
use the manual steps below.

From the repository root, the equivalent manual steps are running a compatible Chronicle 18.4.0+
development kernel and then `./gradlew :Samples:Java:ChronicleSpringBoot:bootRun` with JDK 17
active on `JAVA_HOME`/`PATH`.

Routes:

- `POST /api/create-task`
- `POST /api/rename-task`
- `GET /api/tasks/by-id?id=<id>`
- `QUERY /api/tasks` with `{ "arguments": {} }`

Create and read a tenant-local task:

```shell
curl -sS -X POST http://localhost:8080/api/create-task \
  -H 'Content-Type: application/json' \
  -H 'x-cratis-tenant-id: tenant-a' \
  -d '{"id":"task-1","title":"First title"}'

curl -sS 'http://localhost:8080/api/tasks/by-id?id=task-1' \
  -H 'x-cratis-tenant-id: tenant-a'
```

Use the returned `eventLogPosition` as the optimistic-concurrency expectation:

```shell
curl -sS -X POST http://localhost:8080/api/rename-task \
  -H 'Content-Type: application/json' \
  -H 'x-cratis-tenant-id: tenant-a' \
  -d '{"id":"task-1","title":"Renamed","expectedSequenceNumber":0}'
```

Reusing a stale position returns HTTP 400 with `reason: "concurrencyViolation"`. The Java handler receives the current tenant-local `TaskView`, records its previous title, and returns `CompletionStage<EventsWithConcurrencyScopes>`. Both commands return server-handled Chronicle events, so successful command envelopes have no client `response` value.
