```java
import io.cratis.arc.concepts.ConceptAs;
import java.math.BigDecimal;
import java.util.UUID;

public record AccountName(String value) implements ConceptAs<String> {
}

public record AccountBalance(BigDecimal value) implements ConceptAs<BigDecimal> {
}

public interface AccountService {
    UUID open(AccountName name, AccountBalance initialBalance);
}

@Command
public record OpenDebitAccount(AccountName name, AccountBalance initialBalance) {
    public UUID handle(@FromServices AccountService accounts) {
        return accounts.open(name, initialBalance);
    }
}
```
