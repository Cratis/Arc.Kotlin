```java
public final class RegisterCustomerValidator implements BlockingCommandValidator<RegisterCustomer> {
    private final CustomerRepository customers;

    public RegisterCustomerValidator(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public Class<RegisterCustomer> getCommandType() {
        return RegisterCustomer.class;
    }

    @Override
    public List<ValidationResult> validate(RegisterCustomer command, CommandContext context) {
        return customers.findById(command.id()).isEmpty()
            ? List.of()
            : List.of(ValidationResult.error("Customer is already registered.", List.of("id")));
    }
}

@Configuration
public class RegisterCustomerValidation {
    @Bean
    public CommandValidator<RegisterCustomer> registerCustomerValidator(CustomerRepository customers) {
        return new BlockingCommandValidatorAdapter<>(new RegisterCustomerValidator(customers));
    }
}
```
