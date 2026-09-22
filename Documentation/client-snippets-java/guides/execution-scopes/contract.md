```java
public interface BlockingCommandExecutionScope {
    void begin(CommandContext context);

    CommandResult<?> complete(CommandContext context, CommandResult<?> result);
}

public interface AsyncCommandExecutionScope {
    void begin(CommandContext context);

    CompletionStage<CommandResult<?>> complete(CommandContext context, CommandResult<?> result);
}
```
