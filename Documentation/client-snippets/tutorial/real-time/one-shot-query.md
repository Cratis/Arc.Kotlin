```kotlin
@JvmStatic
fun allAuthors(@FromServices authors: AuthorRepository): List<Author> = authors.findAll()
```
