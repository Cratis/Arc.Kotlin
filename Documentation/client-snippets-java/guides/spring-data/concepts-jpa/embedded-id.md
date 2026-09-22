```java
@Embeddable
public record UuidId(UUID value) implements ConceptAs<UUID>, Serializable { }

@Entity
@ReadModel
public class UuidRow {
    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "concept_id"))
    public UuidId id;

    @Convert(converter = TextConverter.class)
    @Column(name = "text_field")
    public TextValue textField;

    public UuidRow() { }
}
```
