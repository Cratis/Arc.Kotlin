```java
public final class SubmitOrderValidator implements BlockingCommandValidator<SubmitOrder> {
    private final OrderViewRepository orders;

    public SubmitOrderValidator(OrderViewRepository orders) {
        this.orders = orders;
    }

    @Override
    public Class<SubmitOrder> getCommandType() {
        return SubmitOrder.class;
    }

    @Override
    public List<ValidationResult> validate(SubmitOrder command, CommandContext context) {
        var order = orders.findById(command.id()).orElse(null);
        if (order == null) {
            return List.of(ValidationResult.error("Order does not exist.", List.of("id")));
        }

        return order.status() == OrderStatus.READY_FOR_SUBMISSION
            ? List.of()
            : List.of(ValidationResult.error(
                "Only orders that are ready for submission can be submitted.",
                List.of("id")));
    }
}

@Configuration
public class SubmitOrderValidation {
    @Bean
    public CommandValidator<SubmitOrder> submitOrderValidator(OrderViewRepository orders) {
        return new BlockingCommandValidatorAdapter<>(new SubmitOrderValidator(orders));
    }
}
```
