```kotlin
suspend fun registersACustomer() {
    val scenario = CommandScenario(module, RegisterCustomer::class.java)
    scenario.givenChronicle()
        .events(customerId, CustomerRegistered("Existing"))

    val result = scenario.execute(RegisterCustomer(customerId, "Ada"))

    result.shouldSucceed()
    scenario.chronicle()
        .shouldHaveAppendedEvent(customerId, CustomerRegistered::class.java)
}
```
