```java
record AuditEntry(String orderId) {}
record OrderReceipt(String orderId) {}

@Command
public record CreateOrder(String orderId) {
    public Pair<AuditEntry, OrderReceipt> handle() {
        return new Pair<>(new AuditEntry(orderId), new OrderReceipt(orderId));
    }
}

@HandlesCommandResponseValues({AuditEntry.class})
final class AuditEntryResponseHandler implements BlockingCommandResponseValueHandler {
    @Override
    public boolean canHandle(CommandContext context, Object value) {
        return value instanceof AuditEntry;
    }

    @Override
    public CommandResult<?> handle(CommandContext context, Object value) {
        return CommandResult.success(context.getCorrelationId());
    }
}

@Configuration(proxyBeanMethods = false)
class ResponseHandlerConfiguration {
    @Bean
    CommandResponseValueHandler auditEntryResponseValueHandler() {
        return new BlockingCommandResponseValueHandlerAdapter(new AuditEntryResponseHandler());
    }
}
```
