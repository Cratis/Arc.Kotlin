```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.queries.ObservableState;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

// Chat/ChatPersistence.java
public interface ChatPersistence {
    // Loads a room's messages, oldest first.
    List<ChatMessage> history(String roomName);
}

// Chat/ChatRoom.java
public final class ChatRoom {
    private final Object lock = new Object();
    private final ObservableState<List<ChatMessage>> messages;

    public ChatRoom(List<ChatMessage> history) {
        messages = new ObservableState<>(List.copyOf(history));
    }

    public Flow.Publisher<List<ChatMessage>> messages() {
        return messages;
    }

    // Called for every message that arrives from the broker.
    public void receive(ChatMessage message) {
        synchronized (lock) {
            var history = new ArrayList<>(messages.get());
            history.add(message);
            messages.set(List.copyOf(history));
        }
    }
}

// Chat/ChatService.java
// Loads the history once, the first time a room is asked for.
@Component
public final class ChatService {
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();
    private final ChatPersistence persistence;

    public ChatService(ChatPersistence persistence) {
        this.persistence = persistence;
    }

    public ChatRoom getChatRoom(String name) {
        return rooms.computeIfAbsent(name, key -> new ChatRoom(persistence.history(key)));
    }
}

// Chat/ChatPublisher.java
public interface ChatPublisher {
    void publish(ChatMessageEnvelope envelope);
}

// Chat/ChatMessageId.java
public record ChatMessageId(UUID value) implements ConceptAs<UUID> {
    public static ChatMessageId newId() {
        return new ChatMessageId(UUID.randomUUID());
    }
}

// Chat/ChatMessageEnvelope.java
// The wire format on the broker: the message plus the room it belongs to.
public record ChatMessageEnvelope(
    String roomName, ChatMessageId id, String user, OffsetDateTime sentAt, String message) { }

// Chat/ChatMessage.java
@ReadModel
@AllowAnonymous
public record ChatMessage(ChatMessageId id, String user, OffsetDateTime sentAt, String message) {
    public static Flow.Publisher<List<ChatMessage>> forRoom(String roomName, @FromServices ChatService chatService) {
        return chatService.getChatRoom(roomName).messages();
    }
}

// Chat/SendMessage.java
@Command
@AllowAnonymous
public record SendMessage(String roomName, String user, String message) {
    // Publishes to the broker; the room updates when the message comes back from it.
    public void handle(@FromServices ChatPublisher publisher) {
        publisher.publish(new ChatMessageEnvelope(
            roomName, ChatMessageId.newId(), user, OffsetDateTime.now(ZoneOffset.UTC), message));
    }
}
```
