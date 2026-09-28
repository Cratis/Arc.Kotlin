```kotlin
import io.cratis.arc.chronicle.chronicle
import io.cratis.arc.generated.LibraryArcArtifactModule
import io.cratis.arc.testing.CommandScenario
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

// src/test/kotlin/library/authors/registration/WhenRegisteringAnAuthor.kt
class WhenRegisteringAnAuthor {
    @Test
    fun `records the names under the returned identity`() = runBlocking {
        val scenario = CommandScenario(LibraryArcArtifactModule(), RegisterAuthor::class.java)
        val authorId = AuthorId.new()

        val result = scenario.execute(RegisterAuthor(authorId, AuthorName("J.R.R."), AuthorName("Tolkien")))

        result.shouldSucceed()
        assertEquals(authorId, result.shouldHaveResponse(AuthorId::class.java))
        scenario.chronicle().shouldHaveAppendedEvents(1)
        val registered = scenario.chronicle()
            .shouldHaveAppendedEvent<AuthorRegistered>(authorId.value().toString())
        assertEquals(AuthorRegistered(AuthorName("J.R.R."), AuthorName("Tolkien")), registered)
    }
}
```
