```java
public class UnitOfWorkScope implements BlockingCommandExecutionScope {
    private final UnitOfWork unitOfWork;

    public UnitOfWorkScope(UnitOfWork unitOfWork) {
        this.unitOfWork = unitOfWork;
    }

    @Override
    public void begin(CommandContext context) {
        unitOfWork.begin();
    }

    @Override
    public CommandResult<?> complete(CommandContext context, CommandResult<?> result) {
        if (result.isSuccess()) {
            unitOfWork.commit();
        } else {
            unitOfWork.rollback();
        }
        return null;
    }
}

@Configuration
class ScopeConfiguration {
    @Bean
    CommandExecutionScope unitOfWorkScope(UnitOfWork unitOfWork) {
        return new BlockingCommandExecutionScopeAdapter(new UnitOfWorkScope(unitOfWork));
    }
}
```
