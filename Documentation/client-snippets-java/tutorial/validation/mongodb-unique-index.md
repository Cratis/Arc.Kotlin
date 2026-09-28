```java
@Component
class AuthorIndexes {
    AuthorIndexes(MongoTemplate template) {
        template.indexOps(Author.class)
            .createIndex(new Index().on("name", Sort.Direction.ASC).unique());
    }
}
```
