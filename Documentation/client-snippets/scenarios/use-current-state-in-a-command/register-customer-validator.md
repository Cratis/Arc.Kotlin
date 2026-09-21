```kotlin
@Component
class RegisterCustomerValidator(
    private val customers: CustomerRepository
) : CommandValidator<RegisterCustomer> {
    override val commandType: Class<RegisterCustomer> = RegisterCustomer::class.java

    override suspend fun validate(
        command: RegisterCustomer,
        context: CommandContext
    ): List<ValidationResult> =
        if (customers.findById(command.id) == null) {
            emptyList()
        } else {
            listOf(ValidationResult.error("Customer is already registered.", listOf("id")))
        }
}
```
