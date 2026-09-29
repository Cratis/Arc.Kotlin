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

// Chat/ChatRoom.kt
class ChatRoom {
    private val history = MutableStateFlow<List<ChatMessage>>(emptyList())

    // Holds the room's full history and hands it to every new subscriber.
    val messages: StateFlow<List<ChatMessage>> = history.asStateFlow()

    fun send(user: String, message: String) {
        history.update { it + ChatMessage(ChatMessageId.new(), user, OffsetDateTime.now(ZoneOffset.UTC), message) }
    }
}

// A Spring component, so the application has one ChatService.
@Component
class ChatService {
    private val rooms = ConcurrentHashMap<String, ChatRoom>()

    fun getChatRoom(name: String): ChatRoom = rooms.computeIfAbsent(name) { ChatRoom() }
}

// Chat/ChatRoomPage.kt
data class ChatMessageId(private val id: UUID) : ConceptAs<UUID> {
    override fun value(): UUID = id

    companion object {
        fun new(): ChatMessageId = ChatMessageId(UUID.randomUUID())
    }
}

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
    fun handle(@FromServices chatService: ChatService) {
        chatService.getChatRoom(roomName).send(user, message)
    }
}
```
