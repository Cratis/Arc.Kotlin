```java
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cratis.arc.chronicle.ChronicleCommandScenario;
import io.cratis.arc.chronicle.ChronicleCommandScenarios;
import io.cratis.arc.generated.LibraryArcArtifactModule;
import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import org.junit.jupiter.api.Test;

// src/test/java/library/authors/registration/WhenRegisteringAnAuthor.java
public class WhenRegisteringAnAuthor {
    @Test
    void recordsTheNamesUnderTheReturnedIdentity() {
        var configured = new CommandScenario<>(new LibraryArcArtifactModule(), RegisterAuthor.class);
        ChronicleCommandScenario chronicle = ChronicleCommandScenarios.chronicle(configured);
        var authorId = AuthorId.newId();

        try (var scenario = new BlockingCommandScenario<>(configured)) {
            var result = scenario
                .execute(new RegisterAuthor(authorId, new AuthorName("J.R.R."), new AuthorName("Tolkien")))
                .shouldSucceed();
            assertEquals(authorId, result.shouldHaveResponse(AuthorId.class));
        }

        chronicle.shouldHaveAppendedEvents(1);
        var registered = chronicle.shouldHaveAppendedEvent(authorId.value().toString(), AuthorRegistered.class);
        assertEquals(new AuthorRegistered(new AuthorName("J.R.R."), new AuthorName("Tolkien")), registered);
    }
}
```
