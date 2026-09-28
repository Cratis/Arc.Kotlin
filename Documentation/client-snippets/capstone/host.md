```kotlin
import io.cratis.chronicle.ChronicleOptions
import io.cratis.chronicle.artifacts.IArtifactActivator
import io.cratis.chronicle.artifacts.KnownClientArtifacts
import io.cratis.chronicle.connection.ChronicleConnectionString
import io.cratis.chronicle.sinks.WellKnownSinkTypes
import io.cratis.chronicle.spring.ChronicleProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ChronicleConfiguration {
    @Bean
    fun chronicleOptions(
        properties: ChronicleProperties,
        artifactActivator: IArtifactActivator,
        @Value("\${spring.application.name:Unknown}") applicationName: String
    ): ChronicleOptions = ChronicleOptions(
        connectionString = ChronicleConnectionString.parse(properties.connectionString),
        programIdentifier = properties.programIdentifier ?: applicationName,
        defaultSinkTypeId = properties.defaultSinkTypeId
            ?: System.getenv("CHRONICLE_SINK_TYPE")
            ?: WellKnownSinkTypes.MONGODB,
        autoDiscoverAndRegister = properties.autoDiscoverAndRegister,
        artifacts = KnownClientArtifacts(
            Registered::class,
            Listing::class,
            ListingReducer::class,
            RegistrationReactor::class,
            AuthorRegistered::class,
            Author::class,
            AuthorReducer::class
        ),
        artifactActivator = artifactActivator
    )
}
```
