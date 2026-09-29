```java
import io.cratis.arc.chronicle.ChronicleCommandScenario;
import io.cratis.arc.chronicle.ChronicleCommandScenarios;
import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WhenRegisteringANewAuthor {
    @Test
    void recordsTheFact() {
        var configured = new CommandScenario<>(new LibraryArcArtifactModule(), RegisterAuthor.class);
        ChronicleCommandScenario chronicle = ChronicleCommandScenarios.chronicle(configured);

        try (var scenario = new BlockingCommandScenario<>(configured)) {
            scenario.execute(new RegisterAuthor("author-1", "Jane Austen")).shouldSucceed();
        }

        var registered = chronicle.shouldHaveAppendedEvent("author-1", AuthorRegistered.class);
        assertEquals("Jane Austen", registered.name());
    }
}
```
