```java
@Authorize(policy = "activeSubscription")
@Roles("member")
@Command
public class UpdateProfile {
    public void handle() { }
}
```
