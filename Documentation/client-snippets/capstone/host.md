```kotlin
import io.cratis.chronicle.ChronicleOptions
import io.cratis.chronicle.artifacts.IArtifactActivator
import io.cratis.chronicle.artifacts.KnownClientArtifacts
import io.cratis.chronicle.connection.ChronicleConnectionString
import io.cratis.chronicle.sinks.WellKnownSinkTypes
import io.cratis.chronicle.spring.ChronicleProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@SpringBootApplication
class LibraryApplication

@Configuration(proxyBeanMethods = false)
class ChronicleConfiguration {
    @Bean
    fun chronicleOptions(
        properties: ChronicleProperties,
        artifactActivator: IArtifactActivator,
        @Value("\${spring.application.name:Library}") applicationName: String
    ): ChronicleOptions = ChronicleOptions(
        connectionString = ChronicleConnectionString.parse(properties.connectionString),
        programIdentifier = properties.programIdentifier ?: applicationName,
        defaultSinkTypeId = properties.defaultSinkTypeId ?: WellKnownSinkTypes.MONGODB,
        autoDiscoverAndRegister = properties.autoDiscoverAndRegister,
        artifacts = KnownClientArtifacts(AuthorRegistered::class, Author::class, AuthorReducer::class),
        artifactActivator = artifactActivator
    )
}
```
