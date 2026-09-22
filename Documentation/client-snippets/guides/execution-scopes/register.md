```kotlin
@Component
class UnitOfWorkScope(private val unitOfWork: UnitOfWork) : CommandExecutionScope {
    override fun begin(context: CommandContext) {
        unitOfWork.begin()
    }

    override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
        if (result.isSuccess) {
            unitOfWork.commit()
        } else {
            unitOfWork.rollback()
        }
        return null
    }
}
```
