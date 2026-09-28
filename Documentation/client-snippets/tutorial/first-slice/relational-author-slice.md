```kotlin
@Entity
@Table(name = "authors")
class AuthorRow(
    @field:Id var id: UUID = UUID(0, 0),
    @field:Column(name = "name", nullable = false) var name: String = ""
)

@Command
data class RegisterAuthor(val id: AuthorId, val name: AuthorName) {
    suspend fun handle(@FromServices authors: AuthorRepository) {
        authors.save(Author(id, name))
    }
}

@ReadModel
data class Author(val id: AuthorId, val name: AuthorName) {
    companion object {
        @JvmStatic
        fun allAuthors(@FromServices queries: JpaObservableQuery): Flow<List<Author>> =
            queries.observe(Author::class.java, JpaSnapshotQuery { em ->
                em.createQuery("select a from AuthorRow a", AuthorRow::class.java)
                    .resultList.map { Author(AuthorId(it.id), AuthorName(it.name)) }
            })
    }
}
```
