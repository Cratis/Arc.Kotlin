```java
import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;

public class WhenRecordingAnAuthor {
    @Test
    void recordsTheAuthor() {
        var id = new AuthorId(UUID.randomUUID());
        var name = new AuthorName("Ada Lovelace");
        var recorded = new ArrayList<AuthorId>();
        AuthorRegistration registration = (registeredId, registeredName) -> recorded.add(registeredId);

        var scenario = new CommandScenario<>(new LibraryArcArtifactModule(), RecordAuthor.class)
            .addService(AuthorRegistration.class, registration);

        // BlockingCommandScenario owns a bounded dispatcher, so a JUnit test needs no coroutine.
        try (var blocking = new BlockingCommandScenario<>(scenario)) {
            blocking.execute(new RecordAuthor(id, name)).shouldSucceed();
        }

        assertEquals(List.of(id), recorded);
    }
}
```
