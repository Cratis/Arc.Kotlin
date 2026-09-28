```kotlin
@Component
class AuthorIndexes(template: MongoTemplate) {
    init {
        template.indexOps(Author::class.java)
            .createIndex(Index().on("name", Sort.Direction.ASC).unique())
    }
}
```
