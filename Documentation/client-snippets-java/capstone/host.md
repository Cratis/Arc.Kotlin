```java
import io.cratis.chronicle.ChronicleOptions;
import io.cratis.chronicle.artifacts.IArtifactActivator;
import io.cratis.chronicle.artifacts.KnownClientArtifacts;
import io.cratis.chronicle.connection.ChronicleConnectionString;
import io.cratis.chronicle.sinks.WellKnownSinkTypes;
import io.cratis.chronicle.spring.ChronicleProperties;
import java.util.List;
import kotlin.jvm.JvmClassMappingKt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SpringBootApplication
public class LibraryApplication { }

@Configuration(proxyBeanMethods = false)
public class ChronicleConfiguration {
    @Bean
    public ChronicleOptions chronicleOptions(ChronicleProperties properties, IArtifactActivator activator,
                                             @Value("${spring.application.name:Library}") String applicationName) {
        var artifacts = new KnownClientArtifacts(List.of(
            JvmClassMappingKt.getKotlinClass(AuthorRegistered.class),
            JvmClassMappingKt.getKotlinClass(Author.class),
            JvmClassMappingKt.getKotlinClass(AuthorReducer.class)));
        var name = properties.getProgramIdentifier() == null ? applicationName : properties.getProgramIdentifier();
        var sink = properties.getDefaultSinkTypeId() == null
            ? WellKnownSinkTypes.MONGODB : properties.getDefaultSinkTypeId();
        return new ChronicleOptions(ChronicleConnectionString.Companion.parse(properties.getConnectionString()),
            name, sink, properties.getAutoDiscoverAndRegister(), artifacts, activator);
    }
}
```
