```kotlin
package library.specs

import io.cratis.arc.generated.LibraryArcArtifactModule
import io.cratis.arc.testing.CommandScenario
import kotlinx.coroutines.runBlocking
import library.authors.AuthorId
import library.authors.AuthorName
import library.authors.AuthorRegistration
import library.authors.RecordAuthor
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class WhenRecordingAnAuthor {
    @Test
    fun `records the author`() = runBlocking {
        val id = AuthorId(UUID.randomUUID())
        val name = AuthorName("Ada Lovelace")
        val recorded = mutableListOf<Pair<AuthorId, AuthorName>>()
        val registration = object : AuthorRegistration {
            override suspend fun register(id: AuthorId, name: AuthorName) {
                recorded += id to name
            }
        }

        val result = CommandScenario(LibraryArcArtifactModule(), RecordAuthor::class.java)
            .addService(AuthorRegistration::class.java, registration)
            .execute(RecordAuthor(id, name))

        result.shouldSucceed()
        assertEquals(listOf(id to name), recorded)
    }
}
```
