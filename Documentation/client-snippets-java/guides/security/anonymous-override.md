```java
@ReadModel
@Authorize
public record AuthenticationQueryItem(String message) {
    // Overrides the class: anyone may subscribe to this one.
    @AllowAnonymous
    public static Flow.Publisher<AuthenticationQueryItem> anonymous(
        @FromServices AuthenticationQuerySource source) {
        return source.observeAnonymous();
    }

    // Declares nothing, so the class-level @Authorize applies.
    public static Flow.Publisher<AuthenticationQueryItem> authenticated(
        @FromServices AuthenticationQuerySource source) {
        return source.observeAuthenticated();
    }
}
```
