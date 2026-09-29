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

// Chat/ChatRoom.java
public final class ChatRoom {
    private final Object lock = new Object();

    // Holds the room's full history and hands it to every new subscriber.
    private final ObservableState<List<ChatMessage>> messages = new ObservableState<>(List.of());

    public Flow.Publisher<List<ChatMessage>> messages() {
        return messages;
    }

    public void send(String user, String message) {
        synchronized (lock) {
            var history = new ArrayList<>(messages.get());
            history.add(new ChatMessage(ChatMessageId.newId(), user, OffsetDateTime.now(ZoneOffset.UTC), message));
            messages.set(List.copyOf(history));
        }
    }
}

// Chat/ChatService.java
// A Spring component, so the application has one ChatService.
@Component
public final class ChatService {
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();

    public ChatRoom getChatRoom(String name) {
        return rooms.computeIfAbsent(name, key -> new ChatRoom());
    }
}

// Chat/ChatMessageId.java
public record ChatMessageId(UUID value) implements ConceptAs<UUID> {
    public static ChatMessageId newId() {
        return new ChatMessageId(UUID.randomUUID());
    }
}

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
    public void handle(@FromServices ChatService chatService) {
        chatService.getChatRoom(roomName).send(user, message);
    }
}
```
