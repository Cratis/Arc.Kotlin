```kotlin
import io.cratis.arc.concepts.ConceptAs
import java.math.BigDecimal
import java.util.UUID

data class AccountName(private val rawValue: String) : ConceptAs<String> {
    override fun value(): String = rawValue
}

data class AccountBalance(private val rawValue: BigDecimal) : ConceptAs<BigDecimal> {
    override fun value(): BigDecimal = rawValue
}

interface AccountService {
    suspend fun open(name: AccountName, initialBalance: AccountBalance): UUID
}

@Command
data class OpenDebitAccount(val name: AccountName, val initialBalance: AccountBalance) {
    suspend fun handle(@FromServices accounts: AccountService): UUID =
        accounts.open(name, initialBalance)
}
```
