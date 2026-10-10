```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import java.time.LocalDate

@Command
data class BorrowBook(@CommandKey val bookId: String, val memberEmail: String, val dueDate: LocalDate) {
    fun handle(): BookBorrowed = BookBorrowed(memberEmail, dueDate)
}
```
