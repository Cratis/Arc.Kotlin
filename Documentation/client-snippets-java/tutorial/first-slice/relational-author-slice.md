```java
@Entity
@Table(name = "authors")
class AuthorRow {
    @Id UUID id;
    @Column(name = "name", nullable = false) String name;
    protected AuthorRow() {}
    AuthorRow(UUID id, String name) { this.id = id; this.name = name; }
}

@Command
@AllowAnonymous
public record RegisterAuthor(AuthorId id, AuthorName name) {
    public void handle(@FromServices AuthorRepository authors) {
        authors.save(new Author(id, name));
    }
}

@ReadModel
@AllowAnonymous
public record Author(AuthorId id, AuthorName name) {
    public static Flow.Publisher<List<Author>> allAuthors(@FromServices JpaObservableQuery queries) {
        return queries.observePublisher(Author.class, em ->
            em.createQuery("select a from AuthorRow a", AuthorRow.class).getResultList().stream()
                .map(row -> new Author(new AuthorId(row.id), new AuthorName(row.name))).toList());
    }
}
```
