---
title: Create and validate commands
description: Define model-bound Arc commands, resolve Spring services, validate requests, and use asynchronous handlers.
---

## Define a command

Annotate a Kotlin data class or Java record with `@Command`. Add an instance method named `handle`. KSP generates a reflection-free `CommandHandler`; do not create a controller or handler class.

A handler may return `Unit`/`void`, a response value, a Kotlin suspend result, or a Java `CompletionStage`. Unannotated `handle` parameters are resolved from Spring. Java `Optional<T>` parameters first consume a matching provided value, then preserve owned read-model absence as `Optional.empty()`, and otherwise resolve an ordinary Spring `T` service; raw, wildcard, nested, nullable, and Kotlin-authored `Optional` signatures are rejected. The generated adapter executes inside Arc's bounded application coroutine scope; it does not use `GlobalScope` or `ThreadLocal` request state.

`CommandHandlerArgumentResolver` and its public suspending resolution methods are generated-invocation SPIs: they must be public because generated handlers are emitted into consumer packages. Ordinary Java code invokes the generated `CommandHandler` through Arc's command pipeline, including Java `CompletionStage` handlers; it is not expected to call Kotlin suspend resolver methods directly.

```kotlin
@Command
@AllowAnonymous
data class CreateTask(val title: String) {
    suspend fun handle(repository: TaskRepository): TaskCreated {
        val task = repository.create(title)
        return TaskCreated(task.id, task.title)
    }
}
```

## Prepare handler values

Add an optional public instance `provide` method when `handle` needs fetched or computed data. It runs after authorization and validation, but before `handle`; the `/validate` route never invokes it. Its parameters resolve from the current Spring scope. KSP generates direct calls for regular, suspend, and `CompletionStage` methods.

```kotlin
@Command
data class CompleteTask(val taskId: TaskId) {
    suspend fun provide(tasks: Tasks): Task = tasks.get(taskId)

    fun handle(task: Task, audit: AuditLog): TaskCompleted {
        audit.record(task.id)
        return TaskCompleted(task.id)
    }
}
```

Return one value, a `Pair`, `Triple`, `CommandProvidedValues`, or an `ArcOneOf` alternative. Provided values are matched to `handle` parameters in declaration order and each match is consumed once; unmatched parameters fall back to Spring. A returned `CommandResult`, `ValidationResult`, non-empty validation-result iterable/array, or `AuthorizationResult` is a control signal and can short-circuit the handler. Kotlin handlers can use `commandProvidedValuesOf(first, second)` and `commandResponseValuesOf(first, second)` for explicit ordered aggregates. Java callers retain the `CommandProvidedValues.of(...)` and `CommandResponseValues.of(...)` factories and builders.

## Return aggregate responses

A command response can combine server-consumed values with one value sent to the client. Arc recursively processes typed `Pair` and `Triple` members, the selected value inside `ArcOneOf`, and nested `CommandResult` values by merging their result state and processing a present successful response. After built-in values and values declared by `@HandlesCommandResponseValues` are classified as handled, a client-bearing response must leave exactly one client leaf. A handled-only response may leave none. Two or more possible client leaves are ambiguous and KSP stops compilation with `ARCKSP0109`.

Declare custom handled types on the response handler. The declaration is static metadata; `canHandle` remains the runtime decision, and the handler must also be registered with the command pipeline. In Spring Boot, register a `CommandResponseValueHandler` bean. If a value was statically classified as handled but no registered handler accepts it, Arc fails closed with an unsuccessful `CommandResult` instead of exposing that value as the client response.

```kotlin
data class AuditEntry(val orderId: String)
data class OrderReceipt(val orderId: String)

@Command
data class CreateOrder(val orderId: String) {
    fun handle(): Pair<AuditEntry, OrderReceipt> =
        AuditEntry(orderId) to OrderReceipt(orderId)
}

@Component
@HandlesCommandResponseValues(AuditEntry::class)
class AuditEntryResponseHandler : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = value is AuditEntry

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
        if (value !is AuditEntry) {
            return CommandResult.error(context.correlationId, "Unsupported response value.")
        }
        // Persist value with an injected service in a real handler.
        return CommandResult.success(context.correlationId)
    }
}
```

Java handlers use ordinary annotation array syntax and the blocking or asynchronous adapters. Expose the adapter as a Spring bean; annotating the Java handler declares its handled values but does not register it.

```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandResponseValueHandler;
import io.cratis.arc.commands.HandlesCommandResponseValues;
import io.cratis.arc.java.BlockingCommandResponseValueHandler;
import io.cratis.arc.java.BlockingCommandResponseValueHandlerAdapter;
import io.cratis.arc.results.CommandResult;
import kotlin.Pair;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

record AuditEntry(String orderId) {}
record OrderReceipt(String orderId) {}

@Command
public record CreateOrder(String orderId) {
    public Pair<AuditEntry, OrderReceipt> handle() {
        return new Pair<>(new AuditEntry(orderId), new OrderReceipt(orderId));
    }
}

@HandlesCommandResponseValues({AuditEntry.class})
final class AuditEntryHandler implements BlockingCommandResponseValueHandler {
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
        return new BlockingCommandResponseValueHandlerAdapter(new AuditEntryHandler());
    }
}
```

Use `AsyncCommandResponseValueHandler` with `AsyncCommandResponseValueHandlerAdapter` when the Java handler returns `CompletionStage<CommandResult<?>>`. The Java-callable `CommandResult.success(...)`, `invalid(...)`, `unauthorized(...)`, `error(...)`, and `exception(...)` factories create handler outcomes without Kotlin-only syntax.

KSP currently discovers `@HandlesCommandResponseValues` only on declarations visible in the command's source compilation; an annotation present only in a dependency binary does not classify the returned type. Keep the declaration source-visible when generated proxy or OpenAPI metadata depends on it. `CommandResponseValues` stores an erased `List<Any>` and is therefore intentionally untyped to KSP: its contents can be processed dynamically at runtime but cannot supply a client response type for generated TypeScript or OpenAPI. Prefer typed `Pair`, `Triple`, `ArcOneOf`, and `CommandResult<T>` shapes when generated response metadata is required. These rules document Arc.Kotlin's current JVM contract and do not claim aggregate-response parity with Arc on .NET.

## Add validation

Register a Spring bean implementing `CommandValidator<T>`. Return immutable validation results; an error rejects execution. `POST <command-route>/validate` runs the same command pipeline without invoking the handler.

```kotlin
@Component
class CreateTaskValidator : CommandValidator<CreateTask> {
    override val commandType = CreateTask::class.java

    override suspend fun validate(command: CreateTask, context: CommandContext): List<ValidationResult> =
        if (command.title.isBlank()) {
            listOf(ValidationResult(ValidationResultSeverity.Error, "A task title is required.", listOf("title")))
        } else {
            emptyList()
        }
}
```

Without an explicit threshold, only errors block execution. Use `@TreatWarningsAsErrors` to set `Information` as the maximum nonblocking severity, so warnings and errors block. Callers can set `X-Allowed-Severity` to a severity name or wire number; feedback numerically above that threshold blocks, and invalid header values produce `malformedRequest`.

When Spring has a Jakarta `Validator` bean, the starter automatically adds a command filter. It validates the command and nested `@Valid` object, array, iterable, and map values, maps paths such as `items[0].name` into Arc members, terminates safely on cyclic graphs, and returns ordinary `rule` validation results before `provide` or `handle` runs.

Use `ConceptValidator<TConcept>` for a reusable host-neutral invariant on a `ConceptAs<T>` type. `DefaultCommandValidationFilter` and `DefaultQueryValidationFilter` accept concept validators and walk reachable object, record, collection, array, and map values without requiring each owner to repeat the rule. Matching uses the exact validator `conceptType`; failures are attached to the owning member path. The default Spring filter beans auto-discover `CommandValidator` and `QueryValidator` beans, but not `ConceptValidator` beans, so an application using concept validators must provide replacement default filter beans with the desired validator lists.

## Protect the command

Use `@AllowAnonymous`, `@Authorize(policy = "...")`, `@Authorize(roles = ["..."])`, or repeatable `@Roles`. Arc captures the servlet or Spring Security principal at request entry before suspending.

Applications can register ordered `AuthenticationHandler` beans, or Java `AsyncAuthenticationHandler` beans returning `CompletionStage`. Arc runs the chain before protected Arc endpoints. The first successful result supplies the Arc principal; failures are aggregated into a generic unauthorized response, and anonymous results let later handlers try. Kotlin consumers can exhaustively `when` on `AuthenticationResult.outcome`, whose variants are `AuthenticationOutcome.Authenticated`, `Failed`, and `Anonymous`. The client-readable `.cratis-identity` cache cookie is deliberately excluded from authentication input.

Spring Security remains optional. When present, Arc can map its captured `Authentication`; without it, the servlet principal is used. An application-supplied Arc `Authentication` service replaces the default handler chain.

## Use Kotlin and Java Core conveniences

Kotlin extensions keep host-neutral code concise without changing the Java ABI. `ServiceResolver.resolve<T>()` and `require<T>()` provide reified service lookup; `commandProvidedValuesOf(...)` and `commandResponseValuesOf(...)` preserve ordered aggregate values; and `CommandResult` supports `fold`, `getOrThrow`, `onSuccess`, and `validationOrNull`. These extensions are `@JvmSynthetic` where a Kotlin function shape would be awkward from Java.

Java consumers can implement ordinary blocking or `CompletionStage` contracts and wrap them with the adapters in `io.cratis.arc.java`. Command adapters cover filters, authorization filters, validators, execution scopes, response-value handlers, manual handlers, and authorization policies. `JavaAsyncScope` owns a structured coroutine scope over a caller-supplied executor and creates cancellable `CompletionStage` command, query, authentication, observable-query, and query-health facades; closing it cancels its operations and only shuts down an executor explicitly transferred with `owningExecutorService`.

## Call the endpoint

Commands accept `POST` with a JSON body. Conventional routes use the configured prefix, package segments, and kebab-cased command name. The response is always a `CommandResult` JSON envelope. Kotlin callers can use `fold`, `getOrThrow`, `onSuccess`, and `validationOrNull` without changing that envelope or its Java API. See the [HTTP contract reference](../reference/http-contract.md) for status and header rules.
