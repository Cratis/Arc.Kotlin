---
title: Create and validate commands
description: Define model-bound Arc commands, resolve Spring services, validate requests, and use asynchronous handlers.
---

## Define a command

Annotate a Kotlin class (including a data class) or Java record with `@Command`. Add a public instance method named `handle`. Kotlin's implicit-public visibility is supported for classes, methods, and properties; spelling `public` is optional. KSP generates a reflection-free `CommandHandler`; do not create a controller or handler class. See [Kotlin visibility](../reference/annotations.md#kotlin-visibility) for exclusions and upgrade effects. These annotated command/query generation entry points need no `@IgnoreAutoRegistration` opt-out. This does not mean all discovery is annotation-only: KSP also traverses reachable types and inspects command-like declarations for diagnostics.

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

## Declare body state explicitly

KSP keeps public primary-constructor properties in constructor order, then appends remaining public declared Kotlin properties in name order without duplicates. A public backed body `var`, backed `val`, or `var` with a private setter participates in the generated command contract: Arc's Jackson 3 mapper accepts supplied JSON values for all three. Body initializers are server behavior, not client default literals or evidence that a property is optional. Field/getter validation constraints, `@Valid`, documentation summaries, and a body `@CommandKey` use the same metadata path as constructor properties. A constructor key plus a body key fails with `ARCKSP0106` rather than selecting one silently.

Keep server-only state nonpublic or annotate the member with `@field:JsonIgnore` or `@get:JsonIgnore`. Ignored properties do not enter this metadata graph; `@JsonIgnore(false)` does not exclude a member. Computed getter-only state can be serialized but cannot be advertised as writable command input. KSP reports source-located `ARCKSP0300` for computed or explicitly `READ_ONLY` Kotlin properties reached through a command input graph, including Java record commands and nested models. Use a backed property, ignore the member, or return a separate output model. Output-only computed getters remain supported.

Use default Arc wire names and symmetric access (`AUTO` or explicit `READ_WRITE`) for backed body input properties. Explicit `READ_WRITE` is also supported on backed output-model properties. Body `@JsonProperty` renames, write-only access, and split ignore/explicit-property declarations that the shared descriptor cannot represent fail with `ARCKSP0300`; there are no new manifest access flags. This is not general support for arbitrary application Jackson naming strategies, mixins, or custom serializers. Model inheritance retains separate base links and declared overrides; this change does not flatten or establish inherited command state, keys, or constraints. Declare command state directly on the command.

Previously omitted public body state now adds generated fields, validation, reachable models, and potentially key behavior. Review regenerated clients and resolve newly reported unsupported input shapes; this is a source-contract correction, not a manifest-version change. See [body-property proxy metadata](typescript-proxies.md#include-declared-body-properties).

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

`ArcOneOf` supplies runtime alternatives; it is not a `@GenerateOneOf` union generator. Union generation is not planned.

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

KSP discovers source-visible `@HandlesCommandResponseValues` declarations and dependency declarations exported by Arc KSP in a separate format-1 resource. The Arc Gradle plugin supplies the dependency index automatically; manual KSP builds must [wire the extraction task and argument provider](../reference/configuration.md#dependency-response-handler-metadata). A binary annotation alone, without that resource and index, still does not classify the returned type. This compiler metadata never registers a runtime handler.

Source-visible declarations include handlers generated by another KSP processor in a later round of the same compilation. Arc finalizes response classification after discovery, so earlier commands use those declarations too. A finally handled response type is not a client-model root by itself; it remains in the graph when independently reachable elsewhere, such as through a command input or query model. This does not register the runtime handler or change `canHandle`; runtime registration is still required.

`CommandResponseValues` stores an erased `List<Any>` and is therefore intentionally untyped to KSP: its contents can be processed dynamically at runtime but cannot supply a client response type for generated TypeScript or OpenAPI. Prefer typed `Pair`, `Triple`, `ArcOneOf`, and `CommandResult<T>` shapes when generated response metadata is required. These rules document Arc.Kotlin's current JVM contract and do not claim aggregate-response parity with Arc on .NET.

## Add validation

Register a Spring bean implementing `CommandValidator<T>`. Return immutable validation results; an error rejects execution. `POST <command-route>/validate` runs the same command pipeline without invoking the handler.

```kotlin
@Component
class CreateTaskValidator : CommandValidator<CreateTask> {
    override val commandType = CreateTask::class.java

    override suspend fun validate(command: CreateTask, context: CommandContext): List<ValidationResult> =
        if (command.title.isBlank()) {
            listOf(ValidationResult.error("A task title is required.", listOf("title")))
        } else {
            emptyList()
        }
}
```

`ValidationResult.information`, `ValidationResult.warning`, and `ValidationResult.error` are static factories that name the severity and default members, state, reason, and reason detail. They read the same from Java, and the `ValidationResult` constructor with an explicit `ValidationResultSeverity` remains available.

Without an explicit threshold, only errors block execution. Use `@TreatWarningsAsErrors` to set `Information` as the maximum nonblocking severity, so warnings and errors block. Callers can set `X-Allowed-Severity` to a severity name or wire number; feedback numerically above that threshold blocks, and invalid header values produce `malformedRequest`.

When Spring has a Jakarta `Validator` bean, the starter automatically adds a command filter. It validates the command and nested `@Valid` object, array, iterable, and map values, maps paths such as `items[0].name` into Arc members, terminates safely on cyclic graphs, and returns ordinary `rule` validation results before `provide` or `handle` runs.

Use `ConceptValidator<TConcept>` for a reusable Arc invariant on a `ConceptAs<T>` type. `DefaultCommandValidationFilter` and `DefaultQueryValidationFilter` accept concept validators and walk reachable object, record, collection, array, and map values without requiring each owner to repeat the rule. Matching uses `conceptType.isInstance`, including subtypes; failures are attached to the owning member path. The default Spring filter beans auto-discover `CommandValidator`, `QueryValidator`, and `ConceptValidator` beans in Spring order (`Ordered`, class `@Order`, or factory-method `@Order`). Register concept validators as ordinary Kotlin or Java beans; application-supplied default filter beans remain authoritative. Concept rules run independently of Jakarta `@Valid` and do not require a Jakarta `Validator` bean. These imperative rules are server-only: registering a bean does not invent TypeScript validation or OpenAPI constraints. Directly constructing `ArcAutoConfiguration` outside Spring retains the original typed-validator-only factory behavior; construct the Core default filters with explicit concept validator lists for standalone use.

Cancellation from a concept validator, Kotlin property getter, or Java record accessor propagates through command execution/validation, one-shot queries, and observable opening; the Java async facades cancel their futures. It is not `validatorFailed` feedback and is never silently skipped. Other validator runtime failures still produce safe `validatorFailed` feedback. Ordinary unreadable Kotlin properties remain skipped, while ordinary Java record accessor failures remain exception results. A cancelled nested command marks its root rollback-only even when the caller catches the cancellation.

## Reuse model validation

Register `ModelValidator<T>` for a server-only rule reused by command roots, nested models, and supplied query arguments. Its `modelType: Class<T>` matches the **exact runtime class**, not subclasses or interfaces. `validate(model, context)` is suspending; Java implementations use `BlockingModelValidator` or `AsyncModelValidator` with `BlockingModelValidatorAdapter` or `AsyncModelValidatorAdapter` from `io.cratis.arc.java`. Publish the adapter as a `ModelValidator` Spring bean, not a second Java-specific discovery SPI. The asynchronous adapter reuses Arc's nonblocking, cancellable `CompletionStage` await bridge; neither adapter creates an executor.

`ModelValidationContext` has constructors accepting either `(CommandContext, memberPath)` or `(QueryContext, memberPath)`. Exactly one of `commandContext` and `queryContext` is present. Read `correlationId`, `principal`, `tenantId`, `tenantNamespace`, and `serviceResolver` from this immutable operation view; do not retain it beyond validation. Services, provided command values, and operation context objects are not traversal roots. Only the command instance or each supplied query argument is walked; an omitted Kotlin default has no pre-invocation value and is not manufactured for validation.

Typed `CommandValidator` and `QueryValidator` rules run once before the shared model/concept traversal. A separately registered model rule for the command class is a distinct rule and runs once at the root. Nodes run parent-before-child, model rules before concept rules. Unmatched owners are still traversed. Records use declaration order, Kotlin public properties use alphabetical order, and public-field fallback uses alphabetical order; containers preserve encounter order. Identity tracking prevents cycles and repeated visits within one root. Each query argument is an independent root. Model rules apply to explicitly registered exact scalar, enum, array, and collection runtime classes **before** terminal traversal checks; platform scalars and concepts are not reflected into afterward. Concept matching remains assignable.

Model feedback members are relative to the current node. An empty member list or empty member attaches to the node path; ordinary members gain a dotted prefix, and a leading bracket attaches directly (`items` plus `[0]` becomes `items[0]`). Unlike concept feedback, model members named `value` or `rawValue` are not collapsed. Severity, state, reason, and reason detail are preserved. State is not deep-copied: every feedback field is client-visible application data and must not contain secrets.

An empty returned list means no feedback. Arc validates and snapshots the entire returned list before merging it, rejecting a Java null list, null element, or wrong-type element without retaining partial feedback. Ordinary exceptions from model validation or list extraction become safe node-scoped `validatorFailed` feedback, not exception-message text. Cancellation, including known reflection/stage-wrapped cancellation, propagates; fatal `Error` instances are not swallowed. A model rule throwing a `ValidationFailure` exception still follows this validator-failure policy, not command exception conversion.

Spring collects model beans with its ordered provider, preserving class, `Ordered`, and factory-method ordering, candidate eligibility, and application filter overrides. The original Core filter constructors and Spring public factory signatures remain available. For standalone use, supply the required three iterables `(typedValidators, conceptValidators, modelValidators)` to either default validation filter. Direct concept exclusions use the additive four-iterable constructor described below; model rules themselves cannot be excluded. These imperative rules do not add KSP metadata, TypeScript validation, or OpenAPI constraints, and do not establish Arc .NET parity.

## Exclude a direct concept rule edge

Register immutable `ConceptValidationExclusion(ownerType, member)` values from `io.cratis.arc.validation`. Both Kotlin and Java use the ordinary constructor; Java reads `getOwnerType()` and `getMember()`. Spring collects eligible exclusion beans with its ordered provider when it installs the default filters. Standalone filters accept four required iterables `(typedValidators, conceptValidators, modelValidators, exclusions)` and snapshot the registrations; all previous constructors remain available. Application-supplied default filter beans remain authoritative.

The owner matches the **exact runtime class**. The member must be one direct public Kotlin property, Java record component, or public instance field whose **declared type** implements `ConceptAs`. Inherited readable members are supported when registered against the runtime owner: a registration against a base class does not match a derived instance. JavaBean methods without a corresponding supported property or field are not graph members. Construction rejects blank, unknown, dotted, indexed, wildcard, private, and non-concept members without constructing an owner or executing user getters. A scalar root, a collection element, and a path to a nested member cannot be excluded; register the nested owner type and its direct member instead.

Only `ConceptValidator` execution on that edge is suppressed. Owner rules, exact `ModelValidator` rules (including rules for the concept itself), Jakarta constraints and `@Valid` cascading, root typed validators, and other pipeline filters still run. An excluded edge does not suppress a sibling or another owner. Model/traversal identities and actually validated concept identities are tracked separately: when an ignored edge reaches a shared concept first, a later required edge still validates it and receives its path. Required-first order validates it once, and distinct equal instances validate independently. Model rules run once per identity at their first path even if that edge excludes concept rules. Cycles still terminate; each supplied query argument has independent identity tracking.

Exclusions are server-only registrations, not annotations or client rules. They do not change manifest format 7, generated Jakarta metadata, TypeScript constraints, or OpenAPI schemas. Generated Kotlin and Java scenario/HTTP checks cover the concept/model boundary; HTTP query model checks install an application string-to-model converter rather than claiming built-in arbitrary object binding. The Spring hosting checks separately use its real Jakarta provider to prove excluded edges still reject constraint violations. Neither establishes Arc .NET parity.

## Convert application exceptions to command validation

Implement `io.cratis.arc.validation.ValidationFailure` on an application exception to supply command validation feedback instead of exception details. Kotlin implements `val validationResults: List<ValidationResult>`; Java implements `List<ValidationResult> getValidationResults()`. There is no required framework exception superclass. `CommandResult.fromException(correlationId, exception)` is callable as a static factory from Java and from the Kotlin companion. The legacy `CommandResult.exception(...)` still always creates an ordinary exception result.

```kotlin
class ApplicationFailure(
    override val validationResults: List<ValidationResult>
) : RuntimeException("Internal diagnostic"), ValidationFailure
```

Supply a nonempty list containing only nonnull validation results. Conversion takes an immutable list snapshot and preserves each result's message, members, severity, reason, reason detail, and state. It does not synthesize validation from the exception message, and a valid payload carries no exception message or stack trace. **All payload fields, including `state` and `reasonDetail`, are client-visible application data, even in production. Never put secrets in them.** State objects are retained, not deep-copied or automatically redacted.

Empty, null, or malformed Java payloads and ordinary exceptions (including checked exceptions such as `IOException`) while reading or iterating them fall back to the original ordinary exception result, never success. Partial feedback is discarded if extraction fails. Cancellation takes precedence over the marker, including cancellation thrown by the payload getter or iterator. Fatal `Error` instances propagate rather than being treated as malformed payloads. The factory does not search causes or suppressed exceptions; the existing `CompletionStage` adapter still unwraps its asynchronous failure at the invocation boundary.

The default command pipeline uses this conversion at its existing ordinary exception boundaries: context providers, filters, scope begin, preparation (`provide`), invocation (`handle`), response matching/handling, scope completion, and validate-only context/filter execution. Specialized mappings remain authoritative: missing owned dependencies still produce `dependencyUnavailable`, and exceptions already handled inside default typed/concept/model validators still follow their existing `validatorFailed` policy. This is command-only support, not query exception conversion or a general exception registry.

Severity filtering stays at its existing stages rather than becoming a uniform exception policy:

| Failure origin | Payload severity handling |
| --- | --- |
| Direct `fromException` factory | Retains every severity. |
| Context providers, scope begin, thrown preparation/invocation, scope completion | Retains every severity; even warning-only feedback rejects the result. |
| Filters and response matching/handling | Existing stage threshold removes nonblocking feedback; an entirely filtered payload can leave a successful result. |
| Validate-only | Context failures retain every severity; filter failures use the existing threshold. No preparation, invocation, or scopes run. |

A failed nested execution marks its root rollback-only even when ignored. Cancellation during payload extraction inside an ordinary exception catch is remembered as failure bookkeeping: only successfully begun scopes complete, once in reverse order, before cancellation is rethrown. Existing noncancellable cleanup and independent completion timeouts remain in effect. A pre-scope context failure has nothing to complete; validate-only calls do not enlist in or poison an executing root. Previously accumulated authorization, validation, and ordinary error fragments remain in failed results, and rejected responses are not exposed.

See the [HTTP contract](../reference/http-contract.md#http-statuses) for validation 400, ordinary 500, and mixed-result precedence. This JVM-native capability does not establish Arc .NET parity.

## Protect the command

Use `@AllowAnonymous`, `@Authorize(policy = "...")`, `@Authorize(roles = ["..."])`, or repeatable `@Roles`. Arc captures the servlet or Spring Security principal at request entry before suspending.

Authorization can be declared on the artifact class or on its operation — the command `handle` function or a read-model query function. **The operation wins.** When the operation declares any `@Authorize` or `@Roles`, only its policy, roles, and schemes apply and the class declaration is ignored entirely; the class declaration applies only to operations that declare none. An operation therefore narrows access rather than adding to it: on a class requiring `admin`, a `@Roles("auditor")` operation is reachable by auditors only, never by admins. Repeated `@Roles` on the *same* target still combine, and a caller satisfies a role list by holding any one of its roles.

Applications can register ordered `AuthenticationHandler` beans, or Java `AsyncAuthenticationHandler` beans returning `CompletionStage`. Both handler APIs participate in one Spring-ordered chain before protected Arc endpoints. The first handler returning a principal or a failure decides the outcome; failures are terminal and later handlers do not run. Only anonymous results allow the next handler to try. Unauthorized responses use a generic body without exposing failure details. Kotlin consumers can exhaustively `when` on `AuthenticationResult.outcome`, whose variants are `AuthenticationOutcome.Authenticated`, `Failed`, and `Anonymous`. The client-readable `.cratis-identity` cache cookie is deliberately excluded from authentication input.

Spring Security remains optional. When present, Arc can map its captured `Authentication`; without it, the servlet principal is used. An application-supplied Arc `Authentication` service replaces the default handler chain.

## Use Kotlin and Java Core conveniences

Kotlin extensions keep application code concise without changing the Java ABI. `ServiceResolver.resolve<T>()` and `require<T>()` provide reified service lookup; `commandProvidedValuesOf(...)` and `commandResponseValuesOf(...)` preserve ordered aggregate values; and `CommandResult` supports `fold`, `getOrThrow`, `onSuccess`, and `validationOrNull`. These extensions are `@JvmSynthetic` where a Kotlin function shape would be awkward from Java.

Java consumers can implement ordinary blocking or `CompletionStage` contracts and wrap them with the adapters in `io.cratis.arc.java`. Command adapters cover filters, authorization filters, validators, execution scopes, response-value handlers, manual handlers, and authorization policies. `JavaAsyncScope` owns a structured coroutine scope over a caller-supplied executor and creates cancellable `CompletionStage` command, query, authentication, observable-query, and query-health facades; closing it cancels its operations and only shuts down an executor explicitly transferred with `owningExecutorService`.

## Handle context provider failures

For both `execute` and `validate`, the default pipeline builds context values in provider registration order, resolves the command key, then resolves the dynamic event-stream ID and subject. An ordinary exception stops context construction immediately and returns an unsuccessful `CommandResult` with the supplied correlation identifier. Later providers, filters, preparation, and the handler do not run; no execution scope begins or completes for that failed frame.

A failed nested `execute` marks its root execution rollback-only even when the caller ignores the child result. Provider cancellation remains `CancellationException` (a cancelled `CompletionStage` through `JavaAsyncScope`), and an ignored nested execution cancellation also marks the root rollback-only. `validate` never creates an execution frame or enlists scopes, so an ignored validation result or caught validation cancellation does not itself mark an active root rollback-only. Closed execution tokens and mismatched root, correlation, or tenant-namespace joins remain programmer errors rather than provider error results.

For ordinary failures without a usable `ValidationFailure` payload, Core retains exception messages and stack traces for host logging regardless of `exposeExceptionDetails`. Hosts must apply the existing exception-detail redaction policy before serializing a production response; see the [HTTP contract reference](../reference/http-contract.md).

## Call the endpoint

Commands accept `POST` with a JSON body. Conventional routes use the configured prefix, package segments, and kebab-cased command name. The response is always a `CommandResult` JSON envelope. Kotlin callers can use `fold`, `getOrThrow`, `onSuccess`, and `validationOrNull` without changing that envelope or its Java API. See the [HTTP contract reference](../reference/http-contract.md) for status and header rules.
