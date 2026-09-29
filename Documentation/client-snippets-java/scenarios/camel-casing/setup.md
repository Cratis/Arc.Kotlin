```java
import io.cratis.arc.naming.NamingPolicy;
import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Users/User.java
// Java record components are already camelCase, and a document stores each component under its
// declared name, so this read model is persisted with the fields firstName and emailAddress.
public record User(String id, String firstName, String emailAddress) { }

// Users/ReadModelNaming.java
// The MongoDB integration registers this policy by itself; declaring it only makes the choice
// visible. It keeps the collection names the Chronicle kernel writes (User -> Users). Chronicle's
// JVM client has no camel-case option, so camel-casing collection names here would make Arc read
// collections that the kernel never writes.
@Configuration
public class ReadModelNaming {
    @Bean
    public NamingPolicy namingPolicy() {
        return new DefaultNamingPolicy();
    }
}
```
