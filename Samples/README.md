# Samples

Four runnable applications and one frontend that talks to either plain Arc application. The
Chronicle samples are HTTP-only: `run.sh --chronicle` starts no frontend.

Each sample is the same application written twice, once in Kotlin and once in Java, so you can read
whichever you will actually write. The showcase features mirror the Arc .NET sample application
feature for feature — same pages, same behavior, same routes — against a Spring Boot host.

## Run one

```shell
./run.sh                          # Kotlin, in memory, with the frontend
./run.sh --language java          # the same application written in Java
./run.sh --database mongodb       # store the task board in MongoDB
./run.sh --database postgres      # store the task board in PostgreSQL
./run.sh --chronicle              # the Chronicle-backed sample
./run.sh --no-frontend            # backend only, for curl
./run.sh --sign-in-required       # start signed out, so the anonymous paths are reachable
```

`./run.sh --help` lists every option. Everything a run starts — a database container, the Chronicle
kernel, the frontend — is stopped again when you exit.

The backend listens on `:8080` and the frontend on `:5173` unless `--port` and `--frontend-port` say
otherwise. Open the frontend, not the backend: it proxies `/api` and `/.cratis` through to the
backend so the browser sees a single origin.

## What is here

| Sample | What it is for |
| --- | --- |
| [`Kotlin/SpringBoot`](Kotlin/SpringBoot) | The showcase and the task board, in Kotlin. |
| [`Java/SpringBoot`](Java/SpringBoot) | The same application in ordinary Java, with no Kotlin types in sight. |
| [`Kotlin/ChronicleSpringBoot`](Kotlin/ChronicleSpringBoot) | Commands that append events, a reducer that builds the read model, tenant isolation. |
| [`Java/ChronicleSpringBoot`](Java/ChronicleSpringBoot) | The same, in Java. |
| [`Frontend`](Frontend) | One React application that runs against either plain Arc host unchanged. |

Both plain Arc hosts serve the routes the shared frontend consumes, which is why one frontend covers
both. The Kotlin host additionally exposes runtime-contract endpoints, including calendar queries and
batch task creation, that the frontend does not use. The
generated proxies are regenerated from whichever backend you are about to run and copied in before Vite
starts, so the client can never drift from the server it talks to.

## The showcase

| Page | What it shows |
| --- | --- |
| Task Board | Two commands and a live list. Validation feedback, and a `provide` step that refuses a stale completion. |
| Ticker | The smallest observable query: one read model, one `Flow`, no polling. |
| Live Feed | A command whose response goes to its caller while its effect is pushed to everyone else. |
| Query Showcase | All four query shapes from one read model, and three components sharing one subscription. |
| Conditional Queries | `when(condition)` — a query that is never started, not one whose result is thrown away. |
| Change Stream | What actually travels when a collection changes. Switch Delta to Full and watch the difference. |
| Observable Collection | Commands mutate, a subscription reports. Nothing refetches. |
| Observable Collection (UUID) | The same, keyed by a `UUID` that reaches the browser as a `Guid`. |
| Authentication Queries | A protected read model with one deliberately open query — the login-screen shape. |
| Cross-Cutting Authorization | A command filter and a query filter guarding a whole package by role. |

The toolbar across the top is not decoration. Switching the transport to Server-Sent Events, raising
the connection count, turning on direct mode, or flipping Delta to Full changes how every page below
it talks to the server — and they all keep working, which is the point.

Signing in writes a client principal as a header and a cookie. The cookie is the half that matters:
`EventSource` and the WebSocket handshake cannot carry a custom header, so without it an
authenticated subscription would silently drop to anonymous.

## Choosing a database

`--database` swaps where the task board is stored, and nothing else changes: the same commands,
queries and read model run against all three.

| Value | What runs | What it needs |
| --- | --- | --- |
| `memory` | A bounded in-memory store. | Nothing. This is the default. |
| `mongodb` | Spring Data MongoDB. | Docker, or a MongoDB already on `:27017`. |
| `postgres` | Spring Data JPA. | Docker, or a PostgreSQL already on `:5432`. |

A database already listening on the port is reused rather than fought over, so you do not have to
stop your own. Point elsewhere with `ARC_SAMPLE_MONGODB_URI`, or `ARC_SAMPLE_POSTGRES_URL`,
`ARC_SAMPLE_POSTGRES_USER` and `ARC_SAMPLE_POSTGRES_PASSWORD`.

:::note[Where the MongoDB documents land]
Arc routes MongoDB reads and writes through a tenant-certified `TenantMongoOperations`, so the
database is chosen by the resolved tenant rather than by the path in the connection string. With no
tenant header, that is the driver's default database — look in `test`, not in `arc-samples`.
:::

The Chronicle samples take no `--database`: the Chronicle kernel owns its own storage.

## Prerequisites

JDK 17 on `JAVA_HOME` or `PATH`, and Node.js 22 or newer for the frontend. Docker only for
`--database mongodb`, `--database postgres`, or `--chronicle`.
