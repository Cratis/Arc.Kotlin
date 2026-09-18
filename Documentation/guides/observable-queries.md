---
title: Declare observable queries
description: Return Kotlin Flow, JDK Flow.Publisher, or RxJava 3 Observable from a query method and choose between full snapshots and delta streams.
---

## Choose a return type

An observable query is a static method on a `@ReadModel` companion object (Kotlin) or a Java static method that returns a streaming type. Arc recognises three families:

- Kotlin `kotlinx.coroutines.flow.Flow<T>` or `Flow<List<T>>`
- JDK `java.util.concurrent.Flow.Publisher<T>` or `Publisher<List<T>>`
- RxJava 3 `Observable<T>`, `ObservableSource<T>`, or `Subject<T>` — requires the optional `arc-rxjava3` artifact at runtime (see [RxJava 3 option](#rxjava-3-option))

Arc generates an `OBSERVABLE` performer for any of these. One-shot returns (`T`, `List<T>`, or `Page<T>`) stay request-response.

## Kotlin Flow

Declare the observable method on the companion object and annotate it with `@JvmStatic` so that Java callers and generated performers can reach it:

```kotlin
@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        fun all(@FromServices repository: TaskRepository): Flow<List<TaskView>> =
            repository.observeAll()
    }
}
```

`repository.observeAll()` may return any cold or hot `Flow`. `MutableStateFlow` is the JVM substitute for .NET's `ISubject<T>` when you need a push-side handle and an observable `StateFlow` for HTTP snapshot support — it requires no external dependency:

```kotlin
private val _tasks = MutableStateFlow<List<TaskView>>(emptyList())

@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        fun all(): Flow<List<TaskView>> = _tasks
    }
}
```

## Java Flow.Publisher

Java static methods may return `java.util.concurrent.Flow.Publisher<T>`. Arc wraps it with `asKotlinFlow()` before the observable pipeline:

```java
@ReadModel
@AllowAnonymous
public record TaskView(String id, String title) {
    public static java.util.concurrent.Flow.Publisher<List<TaskView>> all(
        @FromServices TaskRepository repository
    ) {
        return repository.observeAll();
    }
}
```

`java.util.concurrent.SubmissionPublisher<T>` gives you a push-side handle with no external dependency:

```java
private static final java.util.concurrent.SubmissionPublisher<List<TaskView>> publisher =
    new java.util.concurrent.SubmissionPublisher<>();

public static java.util.concurrent.Flow.Publisher<List<TaskView>> all() {
    return publisher;
}
```

### Observable state a snapshot can read

A `SubmissionPublisher` emits, but it holds nothing. Nobody who subscribes learns the current value until the next change — and that is visible on the wire, because Arc answers a snapshot `GET` from the source's current value and reports a source without one as `202 Not Ready` rather than holding the request open.

Kotlin has `MutableStateFlow` for this. The JDK has nothing equivalent, so Arc supplies `ObservableState<T>`:

```java
@Component
public final class TaskSource {
    private final ObservableState<List<TaskView>> tasks = new ObservableState<>(List.of());

    public Flow.Publisher<List<TaskView>> observe() {
        return tasks;
    }

    public void publish(List<TaskView> updated) {
        tasks.set(updated);
    }
}
```

It is a `Flow.Publisher<T>`, so a query method returns it directly. Every subscriber sees the current value first and then each change; a subscriber that falls behind sees only the newest value rather than a backlog, which is the right behavior for state.

Reach for it whenever a Java observable query should also answer a plain `GET`. Kotlin code has no reason to: use `MutableStateFlow` and return a `Flow`.

## RxJava 3 option

If your application already uses RxJava 3, you may return `io.reactivex.rxjava3.core.Observable<T>`, `ObservableSource<T>`, or `subjects.Subject<T>` from the same static-method shape. Add the optional runtime artifact:

```kotlin
implementation("io.cratis:arc-rxjava3:0.0.0-SNAPSHOT")
```

Then declare the query exactly as you would for `Flow`:

```kotlin
@ReadModel
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        fun all(@FromServices repository: TaskRepository): Observable<List<TaskView>> =
            repository.rxObserveAll()
    }
}
```

Java static methods work the same way:

```java
@ReadModel
public record TaskView(String id, String title) {
    public static io.reactivex.rxjava3.core.Observable<List<TaskView>> all(
        @FromServices TaskRepository repository
    ) {
        return repository.rxObserveAll();
    }
}
```

**Backpressure and buffering.** `Observable` has no backpressure protocol. Arc buffers up to 64 values between the RxJava producer and the Kotlin collector; a producer that outruns the collector beyond that fails the flow with `IllegalStateException` rather than dropping values silently. Use a backpressure-aware source such as `Flowable`, or an explicit RxJava operator such as `toFlowable`, when the producer can outpace the consumer.

**Subject as a push handle.** `io.reactivex.rxjava3.subjects.PublishSubject` or `BehaviorSubject` serve the same push-side role as `MutableStateFlow`, but require the `arc-rxjava3` artifact:

```kotlin
private val subject = io.reactivex.rxjava3.subjects.PublishSubject.create<List<TaskView>>()

companion object {
    @JvmStatic
    fun all(): io.reactivex.rxjava3.subjects.Subject<List<TaskView>> = subject
}
```

## Transfer mode: full snapshots versus deltas

Add `transferMode` to the subscription body:

- `full` — every emission carries the complete current snapshot with no change set.
- `delta` — the first emission is a full snapshot; subsequent emissions carry only the change set.
- Omitting `transferMode` keeps the legacy behaviour: every emission carries both the full snapshot and a `changeSet`, with the first one listing every item as added.

For delta mode, Arc needs a stable item identity. When no identity accessor exists, or an extracted key is null or duplicated, Arc falls back to exact serialised JSON identity. That fallback reports additions and removals only — changing any field appears as one removal plus one addition, not a replacement. Use stable, unique item identities when replacements or lower serialisation cost matter.

Change sets do not encode position changes. Do not rely on delta updates to reproduce list reordering.

## Subscription lifetime

Each SSE or WebSocket subscription is independently authorised and isolated. The subscription remains open until the source `Flow` completes, the connection drops, or an `Unauthorized` guard terminates it. Cancelling collection from the Arc side disposes any underlying RxJava subscription or JDK `Flow` subscription.

## Deliberately rejected types

`reactor.core.publisher.Flux` is rejected at compile time. Reactor is part of the Spring ecosystem, and the `Source` module and its `artifacts`/`metadata` packages must remain Spring-free — enforced by `./gradlew checkSpringBoundary`. Accepting `Flux` as a query return type would draw a Reactor compile-time dependency into that boundary.

`org.reactivestreams.Publisher` is also rejected. The `org.reactivestreams` interfaces are superseded by the structurally identical JDK `java.util.concurrent.Flow` types introduced in Java 9. Use `java.util.concurrent.Flow.Publisher<T>` instead.

Use `@QueryTransport(QueryTransportType.OBSERVABLE)` only when you need to annotate a method that Arc would not otherwise recognise as observable. Annotating a non-streaming return with `OBSERVABLE` is a compile-time error.
