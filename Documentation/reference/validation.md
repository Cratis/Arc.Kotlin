---
title: Shared fluent validation contract
description: Check the supported rules, declaration restrictions, registration resources and failure stages for shared Kotlin and Java validation.
---

## Declaration and member contract

Follow the [validation walkthrough](../guides/validation.mdx) for setup, complete sample declarations,
command invocation, client feedback and QUERY requests.

A shared declaration is a public final top-level `FluentModelValidator<T>` with one concrete model
type and a public no-argument constructor. Kotlin permits one `init` block; Java requires
`super(Model.class)` followed by direct fluent chains. The compiler parses the complete restricted
body; it does not execute application constructors. Runtime registration may construct validators
and checks their frozen rules against compiler metadata.

- Use direct public readable member names matching `[A-Za-z_$][A-Za-z0-9_$]*`, not paths or indexes.
- Declare rules alongside the model's source. New selectors over binary-only or inherited members
  fail closed; indexed dependency rules retain their producer's proof.
- Accessors must preserve the wire value: a default getter, Kotlin `get() = field` or Java
  `return this.name` is supported. `field.trim()` or a transforming record accessor is not.
  A backing field alone does not prove identity.
- Do not add fields, initializers outside the allowed body, helper methods, extra constructors,
  local variables, branches, aliases, extension calls, lambdas, interpolated strings or computed
  arguments. Do not shadow Kotlin's standard `Model::class.java` mapping.
- Reading `rules`, registering or evaluating freezes the declaration. Escaped builders cannot
  mutate it afterward. Direct evaluation requires the exact model runtime class.

## Ignore a validation member edge

`io.cratis.arc.validation.IgnoreValidation` is a runtime-retained logical-member annotation.
Kotlin supports `@IgnoreValidation` on a property, `@field:IgnoreValidation`, and
`@get:IgnoreValidation`. Java supports instance fields, bean getters, and record components
through their propagated field/accessor annotations. Compiled record private-field annotations,
including a header annotation with an explicit accessor, are read from the exact compiler classpath
without loading application classes. The Arc plugin supplies `arc.validationClasspath` automatically
and tracks it as a nonincremental classpath input, so private annotation edits invalidate unchanged
consumers even when public ABI and authored fluent metadata do not change.

Manual KSP wiring must supply absolute file URIs for the compilation's directories/JARs joined by
`|`, plus the same nonincremental classpath task input; the Kotlin sample and ContractTests build files
show the main/test-fixtures forms. This is an input-location option, not another generated metadata
resource. Missing evidence, conflicting definitions or unreadable records fail with `ARCKSP0311`;
a visible accessor whole-edge opt-out is sufficient without private annotations. Do not add an
ignore annotation merely to silence missing classpath evidence for an active edge. Unannotated
binary records remain active when their field metadata can be inspected.
There is no class, type-use, constructor-parameter, setter, static member, or executable query-parameter
opt-out. KSP reports unsupported or ambiguous placements with `ARCKSP0311`; language-illegal
annotation targets are rejected by the Kotlin/Java compiler itself.

The annotation cuts **one member edge before access**: direct fluent, concept and Jakarta member
constraints, container-element constraints, and descendant validation through that edge do not run.
A throwing ignored getter and an ignored container's value extractor are not invoked. An active
sibling/alias remains eligible; validating the child separately as a root still validates it.
Genuine property overrides inherit the policy; hidden fields are ambiguous, not overrides.

This is not an access prohibition. Owner-level imperative `ModelValidator` callbacks, Jakarta class
constraints and group-sequence providers still run and may read the member. Their feedback is not
post-filtered, even when it names an ignored member. Serialization, command keys, authorization,
nullability/binding requirements, and executable parameter constraints remain unchanged. It does
not mean `@JsonIgnore`. Existing concept-only exclusions keep their narrower behavior.

Runtime traversal now also visits **bean-only Java getters**, including on models without this
annotation. Public reflected fields retain precedence on the same logical edge. This is an explicit
runtime graph expansion, not new support for computed/bean-only shared wire models: KSP still
requires a representable property and rejects an annotated unbacked generated-model getter.
Source-proved fluent declaration restrictions and the supported graph limits below still apply.

### Jakarta factory ownership

With Boot's default `LocalValidatorFactoryBean`, Arc obtains a dedicated validator from that exact
factory's `usingContext()`, composing `IgnoreValidationTraversableResolver` with
`factory.getTraversableResolver()`. It does not replace the application Validator bean or create,
close, or otherwise own its factory. An application-configured resolver remains the delegate. Automatic adaptation is limited
to the ordinary `LocalValidatorFactoryBean` class: subclasses and custom Validator/ValidatorFactory
facades may override validation behavior, so their owner must explicitly choose the factory adapter.
`Configuration.getDefaultTraversableResolver()` is only the **provider default**, not the current
application resolver; it is not used for composition. This factory-context adaptation needs no
Boot customizer module on Arc's production classpath and remains optional without Jakarta/Boot
validation classes. No fallback factory is installed.

For an application-owned factory, explicitly supply the Java-friendly adapter as the selected
Validator bean:

```java
@Bean
Validator arcAwareValidator(ValidatorFactory applicationValidatorFactory) {
    return IgnoreValidationValidator.fromFactory(applicationValidatorFactory);
}
```

Imports are `jakarta.validation.Validator`, `jakarta.validation.ValidatorFactory`,
`io.cratis.arc.validation.IgnoreValidationValidator`, and Spring's `Bean`. The application must
keep its configured factory alive. This recipe does not turn an arbitrary existing bare Validator
into a factory: custom per-validator context/decorator behavior must be integrated by its owner,
not silently replaced using some unrelated injected factory. Arc uses full-object validation and
executable cascades. Direct `validateProperty`, `validateValue` and unwrapped provider-specific APIs
retain provider semantics; they are not covered by this pre-access graph guarantee.

An opaque Validator remains usable for unaffected statically provable graphs. Discoverable ignored
command/query inputs fail startup with the selected validator class, member and adapter remedy;
late imperative inputs are checked before validation. Unprovable dynamic cascades also require the
adapter, before reading the parent getter. Named Arc command/query filter beans still back off for
application replacements. Custom filters own their own validation policy.

### Manifest format 8 migration

Every command/model/interface property now carries a required JSON boolean `ignoreValidation`.
`true` requires empty **effective** `validationRules` and `validateRecursively: false`; those two
fields alone do not suppress automatic shared traversal. The property, canonical shape, command
key and documentation summary remain present. The Gradle reader rejects missing/nonboolean flags,
inconsistent ignored rules/recursion, and every manifest version other than **8**. Rebuild all
producer/dependency artifacts and regenerate consumers together; format 7 is not accepted.

The public `PropertyDescriptor` has an explicit eight-argument canonical constructor ending in
`summary, ignoreValidation`, and a `getIgnoreValidation()` Java getter. All earlier JVM constructor
signatures remain available and default to `false`. Equality/hash code include the flag. The manual
KSP JSON writer, runtime JSON, generated factories and descriptor merge preserve it; duplicate
producers must agree about the flag instead of unioning rules back into an ignored edge.

The DSL declaration resource, frozen `rules`, compiler expectations and runtime registration
fingerprint retain **all authored rules**. Ignoring a member changes effective evaluation and client
composition, not declaration validity or agreement. OpenAPI retains the property type and required
binding slot; it adds no validation constraints for the ignored edge. This does not introduce general
Jakarta-constraint-to-OpenAPI projection.

## Rule signatures, types and default messages

All rules produce error severity. `withMessage(value: String)` (Java `String`) accepts a literal
message and changes only the immediately preceding rule. Only the first `{PropertyName}` is replaced
with the selected member name, not the qualified nested path. No localization or dynamic message
expression is implied.

In this table, **length-capable** means `String`, a supported collection, or array; **numeric** means
Kotlin `Byte`, `Short`, `Int`, `Long`, `Double`, or JVM `BigInteger`/`BigDecimal`, including Java
primitive/boxed equivalents where applicable. Float members reject. A runtime-supported type must
also satisfy KSP's model/wire-shape contract; the table does not widen supported arrays or graphs.
`n`, `min` and `max` in messages below stand for rendered argument values.

| Signature | Member type and acceptance | Default message |
| --- | --- | --- |
| `notNull()` | Any supported readable member; rejects null (and client undefined) | `'{PropertyName}' must not be empty.` |
| `notEmpty()` | Length-capable; rejects null, empty containers and ECMAScript-trimmed empty strings | `'{PropertyName}' must not be empty.` |
| `minLength(min: Int)` | Length-capable; inclusive lower bound; null passes | `'{PropertyName}' must be at least min characters.` |
| `maxLength(max: Int)` | Length-capable; inclusive upper bound; null passes | `'{PropertyName}' must be at most max characters.` |
| `length(min: Int, max: Int)` | Length-capable; inclusive bounds; null passes | `'{PropertyName}' must be between min and max characters.` |
| `emailAddress()` | `String`; null and empty pass; otherwise bounded email check below | `'{PropertyName}' is not a valid email address.` |
| `phone()` | `String`; null and empty pass; otherwise ASCII digits, ECMAScript whitespace and `()+-` only | `'{PropertyName}' is not a valid phone number.` |
| `url()` | `String`; null and empty pass; otherwise HTTP(S) prefix check below | `'{PropertyName}' is not a valid URL.` |
| `matches(pattern: String)` | `String`; null and empty pass; searches with the portable regex subset below | `'{PropertyName}' is not in the correct format.` |
| `greaterThan(n: Number)` | Numeric; exclusive lower bound; null passes | `'{PropertyName}' must be greater than n.` |
| `greaterThanOrEqual(n: Number)` | Numeric; inclusive lower bound; null passes | `'{PropertyName}' must be greater than or equal to n.` |
| `lessThan(n: Number)` | Numeric; exclusive upper bound; null passes | `'{PropertyName}' must be less than n.` |
| `lessThanOrEqual(n: Number)` | Numeric; inclusive upper bound; null passes | `'{PropertyName}' must be less than or equal to n.` |

Java length parameters are `int`; numeric parameters are `Number`. Length bounds must be literal
nonnegative Int values, from 0 through 2147483647, not Long or Double bounds. Strings count UTF-16
code units, so one supplementary character counts as two; collections/arrays count elements.

The email check requires exactly one `@`, at least one character before it, no ECMAScript whitespace,
and a dot with at least one character between `@` and the dot and at least one after the dot.
It is not Jakarta email validation. Phone allows whitespace-only strings; add `notEmpty` if needed.
URL requires a case-insensitive `http://` or `https://` prefix with at least one following character
that is not LF, CR, U+2028 or U+2029. It does not parse a URI or validate the rest of the string:
`http://a` followed by a newline passes, while a newline immediately after the prefix fails.

ECMAScript whitespace here is U+0009, U+000B, U+000C, U+0020, U+00A0, U+1680, U+2000–U+200A,
U+202F, U+205F, U+3000, U+FEFF, LF, CR, U+2028 and U+2029. It is not Kotlin `isBlank` or Java `\s`;
U+0085 is not in this set. The sample's vectors exercise these distinctions.

### Supported Jakarta and Hibernate annotations

When a supported Jakarta or Hibernate Validator annotation appears on a model property, KSP reads
it and emits the equivalent client rule into the manifest alongside any DSL rules. Annotations not
in this table are ignored; `groups` and `payload` metadata cause the property to reject with
`ARCKSP0301`.

| Annotation | Member type | Client rule(s) emitted |
| --- | --- | --- |
| `@Valid` | Any model | Marks `validateRecursively`; no DSL rule emitted |
| `@NotNull` | Any | `notNull()` |
| `@NotBlank`, `@NotEmpty` | Length-capable | `notEmpty()` |
| `@Size(min, max)` | Length-capable | `minLength(min)` when min > 0 only; `maxLength(max)` when max < `Int.MAX_VALUE` only; `length(min, max)` when both bounds are non-default |
| `@Min(value)` | Numeric | `greaterThanOrEqual(value)` |
| `@Max(value)` | Numeric | `lessThanOrEqual(value)` |
| `@DecimalMin(value, inclusive)` | Numeric | `greaterThanOrEqual(value)` when inclusive (default); `greaterThan(value)` otherwise |
| `@DecimalMax(value, inclusive)` | Numeric | `lessThanOrEqual(value)` when inclusive (default); `lessThan(value)` otherwise |
| `@Positive` | Numeric | `greaterThan(0)` |
| `@PositiveOrZero` | Numeric | `greaterThanOrEqual(0)` |
| `@Negative` | Numeric | `lessThan(0)` |
| `@NegativeOrZero` | Numeric | `lessThanOrEqual(0)` |
| `@Pattern(regexp)` | String | `matches(regexp)`; see [portable patterns](#portable-patterns) |
| `@Email` | String | `emailAddress()`; adds `matches(regexp)` when a non-default regexp is supplied |
| `@Range(min, max)` | Numeric | `greaterThanOrEqual(min)` always (Hibernate's default lower bound 0 is a real constraint even when only `max` is written); `lessThanOrEqual(max)` only when max ≠ `Long.MAX_VALUE` |
| `@Length(min, max)` | Length-capable | Same three-branch mapping as `@Size`: `minLength`, `maxLength`, or `length` |
| `@Digits(integer, fraction)` | Numeric or String | `matches("^[+-]?\\d{1,integer}$")` when fraction=0; `matches("^[+-]?\\d{1,integer}(\\.\\d{1,fraction})?$")` otherwise |
| Arc `@Phone` | String | `phone()` |
| Arc `@Url`, Hibernate `@URL` | String | `url()` |
| Arc `@CreditCard`, Hibernate `@CreditCardNumber` | String | `creditCard()` — server metadata only; proxy generation rejects because the pinned TypeScript client has no credit-card validator |

Jakarta constraint package is `jakarta.validation.constraints`; Hibernate Validator constraints
are `org.hibernate.validator.constraints`. Annotation and DSL rules conjoin into a union;
see [conjunction, duplication and contradictions](#conjunction-duplication-and-contradictions) for
the exact deduplication and contradiction-checking semantics.

### Numeric bounds and values

Although the API accepts `Number`, shared source declarations accept **numeric literals**, not
`BigDecimal("2")`, `new BigInteger("2")`, constants, arithmetic or factory calls. Kotlin's restricted
syntax accepts decimal integer, fractional and exponent forms, an optional leading minus, and
`L`/`f`/`F` suffixes; it does not accept hexadecimal or underscore-separated numbers. Java uses
ordinary parser-proved numeric literals. A Float literal bound may normalize safely; that does not
make a Float **member** safe. Prefer integer or Double literals for clarity.

Both normalized bounds and evaluated values must be finite, within **±9007199254740991**, not
nonzero JavaScript subnormals (absolute value below `Double.MIN_NORMAL`), and decimal-round-trippable
through a JavaScript number. BigDecimal and BigInteger **members** are permitted only within this
bounded domain: this does not promise arbitrary-precision client equality.

A direct JVM validator call throws `IllegalArgumentException` for an out-of-domain numeric value,
for example `9007199254740992L` or `BigDecimal("0.100000000000000001")`, rather than returning a
normal comparison violation. Inside the existing model-validation pipeline, the failed declaration
becomes sanitized error feedback: message `The value could not be validated.`, reason
`validatorFailed`, and the **model path**, such as `input`, not the numeric member path
`input.greater`. A root model has an empty members list. Generated validators guard the client
number domain and return the corresponding failure. This is distinct from a valid-domain number
that simply violates `greaterThan(2)`, which reports its ordinary rule message and member.
Cancellation and fatal errors retain the pipeline's existing propagation behavior.

### Portable patterns

`matches` searches; use anchors for a whole-string condition. The allowed subset is printable ASCII
literals, nonempty positive ASCII character classes, grouping, alternation, quantifiers, anchors,
and `\d`, `\w`, `\s`. `\s` uses the ECMAScript set above. `$` enforces the end of input, including
rejection of a trailing newline for an anchored pattern.

Escape literal closing brackets: regex `^[\]]+$`, written as `"^[\\]]+$"` in Kotlin or Java,
accepts `]` and `]]` but rejects `a`, `]a` and `]` followed by a newline. Ambiguous `[]]`, empty
classes and unescaped closing brackets reject. Also unsupported: dot outside a class, negated or
nested classes, lookarounds, flags, backreferences, Unicode/property escapes, intersections and
possessive quantifiers. This is a portability boundary, not a regex execution-time guarantee.
Existing annotation regex screening is unchanged.

### Conjunction, duplication and contradictions

Rules conjoin; there is no last-declaration override. Exact rule/argument/message duplicates within
one declaration run once after canonicalization; distinct messages and distinct validator classes
remain separate feedback sources. Annotation and DSL metadata form a union of constraints, not a
choice of validation engine. Jakarta still evaluates its own constraints on the server; shared rules
do not replay them or deduplicate all Jakarta feedback.

Within a declaration, length chains must have a nonempty intersection: `minLength(3)` with
`maxLength(2)`, or `notEmpty()` with `maxLength(0)`, rejects. Numeric lower/upper bounds reject when
reversed or when equal with an exclusive endpoint. Inclusive equal endpoints are allowed.
This bounded check is not a solver for contradictions across arbitrary patterns, annotations or
separate validator classes. Generated descriptor merging also requires structurally compatible
models; it unions validation metadata instead of silently choosing one producer's definition.

New shared `creditCard()` rejects because pinned `@cratis/arc` 22.10.4 has no such rule. Existing
Jakarta `@CreditCard` and Hibernate `@CreditCardNumber` metadata and server enforcement remain
unchanged and server-only.

## Supported graph and presence boundaries

The shared graph must be concrete, final and acyclic. Generated command/query validators compose
active nested model rules, including supported collection siblings and indexed paths, without
requiring Jakarta `@Valid`. Annotation-only client behavior is unchanged; `validateRecursively`
metadata alone does not promise general recursive Jakarta client execution.

Inherited/polymorphic graphs, cycles, erased inline-value members, scalar/concept shared roots,
opaque `Any`/`Object` input edges, serialization-ignored/computed shared edges and external mappings of active
shared models reject. Maps containing models remain outside the existing wire-shape contract.
Existing Java object-array property restrictions still apply; supported Kotlin arrays and Java
lists are separate contracts. An explicit validation-ignored edge can be skipped in validation graph
analysis, but its serialization shape must still be representable. This does not claim support for
all polymorphic, inherited or cyclic shared graphs. Use server-only validators for unsupported graphs.

Identity tracking runs once per node within a command or a supplied query-argument root. Separate
query arguments have independent tracking. JSON does not preserve aliases: serializing one aliased
object twice creates two server nodes, so feedback paths may differ from pre-serialization in-memory
validation. Arbitrary JavaScript getters/prototypes, custom Jackson serialization and mutated values
outside the declared wire shape are not a cross-runtime equivalence promise.

Shared query model arguments require request-response RFC QUERY preference and host support.
Omitted Kotlin defaults stay absent before invocation; explicit null is supplied and requires a
nullable parameter. Neither creates a model node to validate before invocation. Supplied objects
are validated, not returned data. See the [complete QUERY example](../guides/validation.mdx#validate-a-supplied-query-model).

## Registration and library packaging

The Arc Gradle plugin supplies a verified compile/runtime dependency index automatically. Generated
artifact modules contribute shared validators to Spring and published in-process scenarios. A
matching bean is deduplicated by exact declaration class; mismatched rules or a fluent bean without
its compiler contribution fail registration. Ordinary imperative validator order and multiplicity
remain unchanged. Direct instantiation alone does not register a shared validator.

Publish library declarations with their model's source producer, compiled classes and generated
resources together. For a module named `Producer`, the relevant inventory is:

| JAR entry | Purpose |
| --- | --- |
| Model and validator `.class` files | Runtime types and bytecode inventory |
| `META-INF/cratis/arc-fluent-validation/Producer.json` | Format-1 declarations authored by this producer; imports are not re-exported |
| `META-INF/cratis/arc-fluent-validation-scope/Producer.json` | Format-1 compilation scope, recording its complete declaration set and index status |
| `io/cratis/arc/generated/ProducerArcArtifactModule.class` | Compiled module with runtime validator linkage |
| `META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule` | ServiceLoader entry naming that module |
| `META-INF/cratis/arc/Producer.json` when artifact metadata is emitted | Format-8 artifact descriptors; not a substitute for declaration or scope metadata |

Validator-only libraries still need the generated module, declaration resource and ServiceLoader
registration; an imported-only root needs a scope and runtime contributions, not a re-exported
local declaration resource. Keep these libraries on **both compile and runtime** dependency
classpaths, normally via `implementation` (or `api` when their types are part of your library API).
Neither `compileOnly` nor `runtimeOnly` alone is sufficient.

Scanning reads bytecode headers and constant-pool linkage without loading application classes.
Missing/conflicting metadata, missing module or service entries and mismatched compile/runtime
inventories fail closed. The compiler parser is packaged with `arc-ksp`, pinned to Kotlin compiler
2.4.20 on JDK 17; it is not an application runtime dependency.

### Manual build tooling

Prefer the plugin. If you maintain manual KSP wiring, use the actual
[Spring Boot sample build](https://github.com/Cratis/Arc.Kotlin/blob/main/Samples/Kotlin/SpringBoot/build.gradle.kts): its `extractFluentIndex`,
`ksp` and `kspKotlin` configuration form one recipe. It resolves compile/runtime **JAR** artifacts,
registers those as extraction inputs, declares the output index, makes KSP depend on extraction,
passes `fluentIndex.map { it.asFile.toURI().toASCIIString() }`, and registers that file as the
nonincremental `arcFluentValidationMetadata` task input with `PathSensitivity.NONE`. It also supplies
`arc.validationClasspath` from the main compile classpath's sorted file URIs and registers that
classpath with `ClasspathNormalizer` as the nonincremental `arcValidationClasspath` input.
Use the actual matching compilation classpath for other source sets; do not substitute a runtime-only
classpath or assume public ABI tracking observes private record annotations.
Do not copy only `ksp { arg("arc.moduleName", ...) }` from a basic onboarding build for shared rules.

The CLI has exactly **three positional arguments**, no named flags:

```bash
java -cp "$ARC_TOOL_CLASSPATH" io.cratis.arc.gradle.ExtractArcFluentValidationMetadataCli \
  "$COMPILE_DEPENDENCY_CLASSPATH" "$RUNTIME_DEPENDENCY_CLASSPATH" "$INDEX_FILE"
```

These variables describe inputs you resolve from your build: `ARC_TOOL_CLASSPATH` contains
`arc-gradle-plugin` and its tool dependencies; the next two are platform-path-separator-delimited
classpath strings for actual compile and runtime dependency artifacts; `INDEX_FILE` is an absolute
output filename. This is the CLI invocation shape, not a standalone dependency resolver. The linked
sample supplies the actual Gradle resolution/task wiring. Include associated compilation classes
and resources when extracting for test or other associated compilations. Do not fabricate an empty
index, even when you expect no dependency rules.

Pass the resulting **absolute file URI**, not a raw path, as `arc.fluentValidationMetadata`.
Set `arc.fluentValidationRoot=true` for main roots, including imported-only applications. The
[production native fixture](https://github.com/Cratis/Arc.Kotlin/blob/main/GradlePlugin/src/test/kotlin/io/cratis/arc/gradle/ArcFluentValidationNativeFunctionalTest.kt)
exercises the plugin's producer/consumer/aggregation wiring, dependency changes and recovery.

Dependency extraction and proxy discovery have **different inputs**. Extraction runs before KSP on
compile and runtime dependency inventories. Proxy generation runs after compilation and needs the
complete compiled **root**: its classes/resources and dependency artifacts on
`--manifest-classpath`, plus `--module-name` identifying the root module. A manifest-only directory
cannot prove runtime registration. The sample's `generateArcProxies` task shows the complete CLI
wiring using main output plus runtime classpath. Root-scope and descriptor checks precede rendering.

## Diagnose by failure stage

Do not assume every metadata failure carries a KSP code. Read the failing task first.

| Stage | Failure and correction |
| --- | --- |
| Dependency-index extraction, **before KSP** | An actionable, **uncoded `GradleException`** reports missing/unindexed/conflicting declarations, missing artifacts or runtime registration, or compile/runtime mismatch. Rebuild/repackage producers and supply complete matching dependency inventories. KSP has not run, so this is not `ARCKSP0310`. |
| KSP, `ARCKSP0310` | Missing or unusable supplied index, or unavailable parser/configuration. Supply the extracted absolute index URI and nonincremental task input; restore the processor's packaged parser dependencies. |
| KSP, `ARCKSP0308` | Unsupported constructor grammar or potentially shadowed Kotlin class mapping. Use only the restricted body and standard `Model::class.java`; remove/rename conflicting declarations or imports. |
| KSP, `ARCKSP0309` | Unsupported member/rule/bound, contradictions, computed or unproved wire members/edges, or ambiguous regex grammar. Use source-proved unchanged members and supported literals, or keep the rule server-only. |
| KSP, `ARCKSP0311` | Unsupported annotation target, hidden state, or unrepresentable ignored wire member. Move the annotation to a supported member edge or rename hidden state. |
| Proxy generation, `ARCVALIDATION_GRAPH` | Unsupported shared graph or query transport. Use the bounded concrete graph and RFC QUERY, or server-only rules. |
| Proxy discovery/root verification | Incomplete root scope, runtime inventory or incompatible descriptors. Supply the complete compiled root and matching dependencies, not manifest-only files. |
| Registration/startup | Runtime declaration rules disagree with compiler metadata, or a fluent bean has no generated contribution. Regenerate and register the matching module. |
| Runtime, ordinary violation | Inspect the rule message and qualified member path. `execute` rejects before the handler; `validate` never invokes it. |
| Runtime, numeric-domain failure | Direct validator throws; the model pipeline returns sanitized `validatorFailed` at the model path, as described above. Do not treat it as an ordinary comparison violation. |

Rejected compilation does not publish current partial declaration/manifest aggregates. Extraction
does not execute constructors or shadowing extension getters.

## Evidence and limits

The executable sources behind the walkthrough are the Kotlin/Java fluent files in
`Samples/Kotlin/SpringBoot` and `ContractTests/TypeScript/contracts/runtime.fluent.contract.ts`.
`Source`'s `FluentModelValidatorTest` supplies the exact semantic vectors, contradiction and
numeric-domain checks. `ArcFluentValidationCompilationTest` proves the restricted Kotlin/Java
source grammar, rejected accessors and generated registration. `FluentValidationContractTest`
uses the unpublished repository fixtures to exercise generated contributions through the published
`CommandScenario` API. `ScenarioFluentValidationTests` counts handler nonexecution in manual-module
repository illustrations; it is not a generated-query transport example.

`ArcFluentValidationMetadataDiscoveryTest` asserts `GradleException` extraction failures;
`ArcFluentValidationNativeFunctionalTest` exercises the production parser and plugin across real
producer/consumer compilations. `SharedValidationRenderingTest` covers generated validator naming
and composition. These named checks are provenance, not a claim that a documentation verifier runs
them. The Kotlin/Java blocking usage adaptations in the guide have not been compiled together as a
new tutorial; the documentation gate is a source/link check, not a compiler or runtime gate.
