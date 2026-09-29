```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.concepts.ConceptAs
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.springframework.stereotype.Component

// Chat/ChatPersistence.kt
interface ChatPersistence {
    // Loads a room's messages, oldest first.
    fun history(roomName: String): List<ChatMessage>
}

// Chat/ChatRoom.kt
class ChatRoom(history: List<ChatMessage>) {
    private val state = MutableStateFlow(history)

    val messages: StateFlow<List<ChatMessage>> = state.asStateFlow()

    // Called for every message that arrives from the broker.
    fun receive(message: ChatMessage) {
        state.update { it + message }
    }
}

// Loads the history once, the first time a room is asked for.
@Component
class ChatService(private val persistence: ChatPersistence) {
    private val rooms = ConcurrentHashMap<String, ChatRoom>()

    fun getChatRoom(name: String): ChatRoom = rooms.computeIfAbsent(name) { ChatRoom(persistence.history(it)) }
}

// Chat/ChatPublisher.kt
interface ChatPublisher {
    fun publish(envelope: ChatMessageEnvelope)
}

// Chat/ChatRoomPage.kt
data class ChatMessageId(private val id: UUID) : ConceptAs<UUID> {
    override fun value(): UUID = id

    companion object {
        fun new(): ChatMessageId = ChatMessageId(UUID.randomUUID())
    }
}

// The wire format on the broker: the message plus the room it belongs to.
data class ChatMessageEnvelope(
    val roomName: String,
    val id: ChatMessageId,
    val user: String,
    val sentAt: OffsetDateTime,
    val message: String
)

@ReadModel
@AllowAnonymous
data class ChatMessage(val id: ChatMessageId, val user: String, val sentAt: OffsetDateTime, val message: String) {
    companion object {
        @JvmStatic
        fun forRoom(roomName: String, @FromServices chatService: ChatService): Flow<List<ChatMessage>> =
            chatService.getChatRoom(roomName).messages
    }
}

@Command
@AllowAnonymous
data class SendMessage(val roomName: String, val user: String, val message: String) {
    // Publishes to the broker; the room updates when the message comes back from it.
    fun handle(@FromServices publisher: ChatPublisher) {
        publisher.publish(ChatMessageEnvelope(roomName, ChatMessageId.new(), user, OffsetDateTime.now(ZoneOffset.UTC), message))
    }
}
```
