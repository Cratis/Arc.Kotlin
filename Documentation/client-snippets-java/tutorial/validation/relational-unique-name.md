```java
@Entity
@Table(name = "authors", uniqueConstraints = @UniqueConstraint(name = "unique_author_name", columnNames = "name"))
class AuthorRow {
    @Id UUID id;
    @Column(name = "name", nullable = false) String name;
    protected AuthorRow() {}
    AuthorRow(UUID id, String name) { this.id = id; this.name = name; }
}
```
