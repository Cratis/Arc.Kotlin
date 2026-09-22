```java
public final class RenameAuthorValidator implements BlockingCommandValidator<RenameAuthor> {
    private final AuthorRepository authors;

    public RenameAuthorValidator(AuthorRepository authors) {
        this.authors = authors;
    }

    @Override
    public Class<RenameAuthor> getCommandType() {
        return RenameAuthor.class;
    }

    @Override
    public List<ValidationResult> validate(RenameAuthor command, CommandContext context) {
        var author = authors.findById(command.id());
        if (author == null) {
            return List.of(ValidationResult.error("Author does not exist.", List.of("id")));
        }

        return author.name().equals(command.newName())
            ? List.of(ValidationResult.error("Choose a different name.", List.of("newName")))
            : List.of();
    }
}

@Configuration
public class RenameAuthorValidation {
    @Bean
    public CommandValidator<RenameAuthor> renameAuthorValidator(AuthorRepository authors) {
        return new BlockingCommandValidatorAdapter<>(new RenameAuthorValidator(authors));
    }
}
```
