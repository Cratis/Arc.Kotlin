```java
void registersACustomer() {
    CommandScenario<RegisterCustomer> configured = new CommandScenario<>(module, RegisterCustomer.class);
    ChronicleCommandScenario chronicle = ChronicleCommandScenarios.chronicle(configured);
    chronicle.given().events(customerId, new CustomerRegistered("Existing"));

    try (BlockingCommandScenario<RegisterCustomer> scenario =
             new BlockingCommandScenario<>(configured)) {
        scenario.execute(new RegisterCustomer(customerId, "Ada")).shouldSucceed();
    }

    chronicle.shouldHaveAppendedEvent(customerId, CustomerRegistered.class);
}
```
