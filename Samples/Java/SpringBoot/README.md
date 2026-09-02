# Java Spring Boot Task Board

This standalone five-minute sample uses Arc's KSP-generated command and query endpoints with a bounded in-memory Spring repository. Its ordinary domain lookup and validator implementation APIs are authored with Java signatures rather than coroutine signatures; it has no controllers, Chronicle dependency, or external infrastructure.

## Five-minute path

From the `Arc.Kotlin` repository root, run the application with JDK 17:

```shell
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :Samples:Java:SpringBoot:bootRun
```

1. Create a task through the generated command execute endpoint:

   ```shell
   curl -sS -X POST http://localhost:8080/api/create-task \
     -H 'Content-Type: application/json' \
     -d '{"title":"Try Arc"}'
   ```

2. Exercise the same command's side-effect-free `/validate` endpoint:

   ```shell
   curl -sS -X POST http://localhost:8080/api/create-task/validate \
     -H 'Content-Type: application/json' \
     -d '{"title":""}'
   ```

3. Copy the created `response.id`, then complete that task. `CompleteTask.provide` asynchronously loads the current view; its typed preparation is passed to `handle`, which returns the completed `TaskView` as the client response:

   ```shell
   curl -sS -X POST http://localhost:8080/api/complete-task \
     -H 'Content-Type: application/json' \
     -d '{"taskId":"<task-id>"}'
   ```

   A missing ID or a task changed after preparation produces Arc validation feedback rather than an exception or a stale update.

4. Read the board with GET or the RFC QUERY method:

   ```shell
   curl -sS 'http://localhost:8080/api/tasks/by-id?id=<task-id>'

   curl -sS -X QUERY http://localhost:8080/api/tasks \
     -H 'Content-Type: application/json' \
     -d '{"arguments":{}}'
   ```

5. Use the generated observable query proxy for `/api/tasks/observe` when a live board is needed.

## Advanced highlights

| Highlight | Where to look | What the tests prove |
| --- | --- | --- |
| Generated execute and `/validate` | `CreateTask.java`, `CreateTaskValidator.java` | Typed command response, ordinary-Java validation through Arc's adapter, and no invalid-state mutation |
| Command key and provide-to-handle flow | `CompleteTask.java`, `TaskCompletionPreparation.java` | The generated handler consumes a revisioned provided value; missing or stale tasks return validation; completion returns a typed `TaskView` |
| GET and RFC QUERY | `TaskView.java` | Both transports return typed query envelopes, including completion state |
| Observable transport | `TaskView.observe` and `TaskRepository.observe` | Tests cover demand and saturation, latest-snapshot replay/coalescing, stale-version rejection, serialized reentrant callbacks and terminal errors, and cleanup after cancellation or callback failure |
| Java ergonomics | `CompletionStage`, `Flow.Publisher`, `BlockingCommandValidator` | Ordinary domain lookup and validator implementations use Java-authored signatures without requiring authors to declare coroutine signatures |

## Generate TypeScript proxies

```shell
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :Samples:Java:SpringBoot:generateArcProxies --no-configuration-cache
```

The real KSP manifest drives generation. Output is untracked under `Samples/Java/SpringBoot/build/generated/arc-proxies`; `CompleteTask.ts`, `TaskView.ts`, and `Observe.ts` are among the checked files. Proxy generation is also part of this sample's `check` task.
