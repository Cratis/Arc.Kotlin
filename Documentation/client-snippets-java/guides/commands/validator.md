```java
@Component
public class CreateTaskValidator implements BlockingCommandValidator<CreateTask> {
    @Override
    public Class<CreateTask> getCommandType() {
        return CreateTask.class;
    }

    @Override
    public List<ValidationResult> validate(CreateTask command, CommandContext context) {
        if (command.title().isBlank()) {
            return List.of(ValidationResult.error("A task title is required.", List.of("title")));
        }
        return List.of();
    }
}
```
