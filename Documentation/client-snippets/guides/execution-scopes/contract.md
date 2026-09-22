```kotlin
public interface CommandExecutionScope {
    fun begin(context: CommandContext)

    suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>?
}
```
