```kotlin
package library.authors

import io.cratis.arc.chronicle.chronicle
import io.cratis.arc.generated.LibraryArcArtifactModule
import io.cratis.arc.testing.CommandScenario
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class WhenRegisteringANewAuthor {
    @Test
    fun `records the fact`() = runBlocking {
        val scenario = CommandScenario(LibraryArcArtifactModule(), RegisterAuthor::class.java)

        val result = scenario.execute(RegisterAuthor("author-1", "Jane Austen"))

        result.shouldSucceed()
        val registered = scenario.chronicle().shouldHaveAppendedEvent<AuthorRegistered>("author-1")
        assertEquals("Jane Austen", registered.name)
    }
}
```
