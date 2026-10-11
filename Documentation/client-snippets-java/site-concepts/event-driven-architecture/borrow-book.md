```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import java.time.LocalDate;

@Command
public record BorrowBook(@CommandKey String bookId, String memberEmail, LocalDate dueDate) {
    public BookBorrowed handle() {
        return new BookBorrowed(memberEmail, dueDate);
    }
}
```
