---
applyTo: "**/*"
---

## JUnit 5 idioms used here

Kotlin tests use backticked, lowercase, behavior-describing method names. There are hundreds of them
and they are the house style:

```kotlin
class EndpointRouteHelperTest {
    @Test
    fun `command route uses package and dotnet compatible kebab casing`() {
        val descriptor = CommandDescriptor(
            "AddAuthor",
            "MyApp.Features.Authors.AddAuthor",
            location = listOf("MyApp", "Features", "Authors")
        )
        assertEquals(
            "/api/my-app/features/authors/add-author",
            EndpointRouteHelper.commandRoute(descriptor)
        )
    }
}
```

Java tests are package-private `final class` with `void` camelCase methods:

```java
final class GeneratedArtifactModuleJavaContractTest {
    @Test
    void generatedModuleIsAJavaFriendlyServiceProvider() {
        // ...
    }
}
```

Observed and expected:

- `org.junit.jupiter.api.Test` with statically imported `org.junit.jupiter.api.Assertions.*`
  (`assertEquals`, `assertTrue`, `assertFalse`, `assertThrows`, `assertNull`, `assertSame`).
  Plain JUnit assertions are the default; do not introduce a new assertion DSL.
- `@BeforeEach` / `@AfterEach`, `@TempDir`, and occasionally `@ParameterizedTest` with `@ValueSource`.
- Coroutine tests wrap the body in `runBlocking { }`.
- `@Nested` and `@DisplayName` are **not** used anywhere in this repository. Do not start.
- Mocking is sparse and module-specific: `io.mockk` in `Integrations/Chronicle` and `ContractTests`,
  Mockito (arriving transitively with `spring-boot-starter-test`) in the Spring Boot and Spring Data
  modules. AssertJ is available through the same starter and is used in a few Spring tests.
  Prefer exercising the real pipeline over mocking it — `arc-testing` exists for exactly that.
