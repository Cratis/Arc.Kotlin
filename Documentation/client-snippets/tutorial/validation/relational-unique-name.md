```kotlin
@Entity
@Table(name = "authors", uniqueConstraints = [UniqueConstraint(name = "unique_author_name", columnNames = ["name"])])
class AuthorRow(
    @field:Id var id: UUID = UUID(0, 0),
    @field:Column(name = "name", nullable = false) var name: String = ""
)
```
