```java
private static final java.util.concurrent.SubmissionPublisher<List<TaskView>> publisher =
    new java.util.concurrent.SubmissionPublisher<>();

public static java.util.concurrent.Flow.Publisher<List<TaskView>> all() {
    return publisher;
}
```
