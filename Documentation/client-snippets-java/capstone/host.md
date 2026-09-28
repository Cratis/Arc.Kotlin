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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChronicleConfiguration {
    @Bean
    public ChronicleOptions chronicleOptions(
        ChronicleProperties properties,
        IArtifactActivator artifactActivator,
        @Value("${spring.application.name:Unknown}") String applicationName
    ) {
        var artifacts = new KnownClientArtifacts(List.of(
            JvmClassMappingKt.getKotlinClass(Registered.class),
            JvmClassMappingKt.getKotlinClass(Listing.class),
            JvmClassMappingKt.getKotlinClass(ListingReducer.class),
            JvmClassMappingKt.getKotlinClass(RegistrationReactor.class),
            JvmClassMappingKt.getKotlinClass(AuthorRegistered.class),
            JvmClassMappingKt.getKotlinClass(Author.class),
            JvmClassMappingKt.getKotlinClass(AuthorReducer.class)));
        var sinkType = properties.getDefaultSinkTypeId();
        if (sinkType == null) sinkType = System.getenv("CHRONICLE_SINK_TYPE");
        if (sinkType == null) sinkType = WellKnownSinkTypes.MONGODB;
        var programIdentifier = properties.getProgramIdentifier() == null
            ? applicationName
            : properties.getProgramIdentifier();
        return new ChronicleOptions(
            ChronicleConnectionString.Companion.parse(properties.getConnectionString()),
            programIdentifier,
            sinkType,
            properties.getAutoDiscoverAndRegister(),
            artifacts,
            artifactActivator);
    }
}
```
