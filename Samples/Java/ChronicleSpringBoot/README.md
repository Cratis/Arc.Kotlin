<!-- Copyright (c) Cratis. All rights reserved. -->
<!-- Licensed under the MIT license. See LICENSE file in the project root for full license information. -->

# Java Chronicle Spring Boot sample

This optional ordinary-Java sample combines generated Arc endpoints with the released Chronicle 4.0.0 Spring Boot starter. It uses `CompletionStage` command/query handlers, the public Arc concurrency builder bridge, and the required `x-cratis-tenant-id` header. The configured `ArcJavaChronicleSample` event store is resolved in exactly that namespace with no default tenant fallback.

Run a compatible Chronicle 16.44.1 development kernel, then:

```shell
./gradlew :Samples:Java:ChronicleSpringBoot:bootRun
```

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
