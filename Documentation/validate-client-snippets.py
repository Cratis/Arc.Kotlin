#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

"""Compile every shared-docs Kotlin and Java snippet against Arc.Kotlin's real modules.

`Documentation/client-snippets/**` and `Documentation/client-snippets-java/**` are not
documentation pages. They are the Kotlin and Java sides of the language tabs the shared
Arc pages render, pulled by the documentation site from this repository and from each
other Arc client repository. One JVM repository answers both tabs, because Kotlin and
Java are two languages a reader might write the same Arc application in. Nothing in a
Markdown file is compiled by anything, so without this gate a snippet is a string nobody
checks: a renamed annotation, a changed signature or an outright invented API keeps
rendering happily on the published site. `validate-doc-snippets.py` is a *source-contract*
check over an allow-list of symbol names - a name it does not list is invisible to it, so
a misspelled type passes. This gate is the one that actually compiles.

A snippet is a fragment, not a file, so each one declares a context - `SNIPPET_CONTEXTS`
for Kotlin, `JAVA_SNIPPET_CONTEXTS` for Java - saying which shape it is, which shared
domain fixtures supply the types it references, which extra imports it needs, and any
prelude no fixture can supply. The snippet body itself is emitted verbatim - never
rewritten - so what compiles is exactly what a reader sees.

The two languages share one snippet id set: every id under `client-snippets` must have a
sibling under `client-snippets-java` and the reverse, because the shared Arc pages expect
every backend to answer every snippet (`warnOnMissingSnippet: true` in the documentation
site's `variant-docs.yml`). A missing sibling is a failure here rather than a tab that
quietly disappears from the published page.

Generated sources go into the `:ContractTests` `documentationSnippet` source set - Kotlin
into its `kotlin` root, Java into its `java` root; see GRADLE_MODULE_RATIONALE below.

Usage:
    python3 Documentation/validate-client-snippets.py
    python3 Documentation/validate-client-snippets.py --self-test
    python3 Documentation/validate-client-snippets.py --dry-run [--print <snippet-id>]

Exit codes:
    0  every snippet compiled
    1  a snippet failed to compile, or the snippet contract was violated
    2  the check could not run at all (no JVM toolchain, no gradlew) - never a pass
"""

import argparse
import re
import subprocess
import sys
import textwrap
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SNIPPET_ROOT = REPO_ROOT / "Documentation" / "client-snippets"
JAVA_SNIPPET_ROOT = REPO_ROOT / "Documentation" / "client-snippets-java"

# Why :ContractTests and the `documentationSnippet` source set:
#
#   * The snippets need Arc's own types (:Source), CommandScenario (:Testing), Spring's
#     @Component/@Bean (:Integrations:SpringBoot), MongoObservableQuery and Spring Data's
#     Criteria (:Integrations:SpringDataMongo), JUnit and kotlin.test. No single existing
#     source set in this workspace covers all of that: :Source has no Spring, :Testing has
#     no Spring and no Spring Data, :Integrations:SpringDataMongo has no :Testing.
#   * :ContractTests already exists to prove cross-module contracts, is never published,
#     and is excluded from binary-compatibility validation - so widening its test
#     classpath costs no consumer anything.
#   * `documentationSnippet` extends `testImplementation` (Source, Testing through
#     :Integrations:Chronicle's compileOnlyApi, SpringBoot, OpenApi, JUnit, the contract
#     test fixtures) and adds SpringDataMongo and kotlin-test. It is deliberately a
#     separate source set: nothing wires it into `check` or `build`, so generated snippet
#     sources can never collide with the contract tests or reach the Jupiter
#     test-declaration checker.
# The source set is declared in ContractTests/build.gradle.kts.
GRADLE_TASK = ":ContractTests:compileDocumentationSnippetKotlin"
GENERATED_DIR = REPO_ROOT / "ContractTests" / "src" / "documentationSnippet" / "kotlin"

# The Java half of the same source set. Java has no coroutines, so a Kotlin `suspend fun`
# becomes either a plain method or one returning a `CompletionStage`, and the Java-facing
# SPIs in `io.cratis.arc.java` (Blocking*/Async* plus their adapters) are what a Java
# application actually implements. Java's one-public-top-level-type-per-file rule means a
# snippet declaring several types cannot be emitted as a bare file, so every Java snippet is
# nested inside a generated wrapper interface: members of an interface are implicitly public
# and static, which is exactly the shape a top-level declaration needs, and the wrapper also
# scopes the snippet's own names so two snippets can both declare `RegisterAuthor`.
JAVA_GRADLE_TASK = ":ContractTests:compileDocumentationSnippetJava"
JAVA_GENERATED_DIR = REPO_ROOT / "ContractTests" / "src" / "documentationSnippet" / "java"
JAVA_SNIPPET_PACKAGE = "io.cratis.arc.documentation.javasnippets"
JAVA_FIXTURE_PACKAGE = f"{JAVA_SNIPPET_PACKAGE}.fixtures"
JAVA_SNIPPET_DIR = JAVA_GENERATED_DIR.joinpath(*JAVA_SNIPPET_PACKAGE.split("."))
JAVA_FIXTURE_DIR = JAVA_GENERATED_DIR.joinpath(*JAVA_FIXTURE_PACKAGE.split("."))


def _package_chain(deepest: Path, stop: Path) -> tuple[Path, ...]:
    """Every directory from `deepest` up to but excluding `stop`, deepest first.

    A Java package is a directory chain, so cleanup has to walk the whole chain back out.
    Deriving it from the package name means a renamed package cannot leave a stray empty
    tree behind the way a hand-maintained list of parents can.
    """
    chain: list[Path] = []
    current = deepest
    while current != stop:
        chain.append(current)
        current = current.parent
    return tuple(chain)


# Deepest first. rmdir only removes an empty directory, so a directory that still holds
# something a human put there is left alone rather than silently deleted.
GENERATED_PRUNE_DIRS = (
    GENERATED_DIR,
    JAVA_FIXTURE_DIR,
    *_package_chain(JAVA_SNIPPET_DIR, JAVA_GENERATED_DIR),
    JAVA_GENERATED_DIR,
    GENERATED_DIR.parent,
)

FENCE_RE = re.compile(r"```([^\s`]+)[^\n]*\n(.*?)\n```", re.DOTALL)
IMPORT_DIRECTIVE_RE = re.compile(r"^import\s+[A-Za-z_][A-Za-z0-9_.`]*(?:\s+as\s+[A-Za-z_][A-Za-z0-9_]*)?$")
JAVA_IMPORT_DIRECTIVE_RE = re.compile(r"^import\s+(?:static\s+)?[A-Za-z_][A-Za-z0-9_.]*(?:\.\*)?;$")

SNIPPET_LANGUAGE = "kotlin"
JAVA_SNIPPET_LANGUAGE = "java"

# A client with no equivalent workflow keeps its tab visible with an explicit statement
# instead of disappearing from the tab group. Such a snippet is a `text` fence containing
# this marker, and is deliberately not compiled.
UNSUPPORTED_FENCE_LANGUAGE = "text"
UNSUPPORTED_MARKER = "does not support this workflow yet"

SNIPPET_PACKAGE_ROOT = "io.cratis.arc.documentation.snippets"
FIXTURE_PACKAGE_ROOT = "io.cratis.arc.documentation.snippets.fixtures"

# Strings a JVM-less machine produces instead of a compiler verdict. Seeing one of these
# means the gate could not run - which is an unknown, never a pass.
TOOLCHAIN_MISSING_MARKERS = (
    "Unable to locate a Java Runtime",
    "JAVA_HOME is not set",
    "No Java compiler found",
    "No matching toolchains found",
    "no Java Development Kit",
)


class SnippetError(Exception):
    """The exception that is thrown when a snippet violates the snippet contract."""


class ToolchainMissing(Exception):
    """The exception that is thrown when no JVM toolchain is available to compile with."""


@dataclass(frozen=True)
class DomainFixture:
    """Supporting domain types shared by the snippets of one domain.

    Emitted once into its own package and made visible to a snippet by listing the fixture
    in `SnippetContext.fixtures`; every name in `types` becomes an explicit import. Explicit
    imports (rather than a star import) are what let a snippet that declares one of these
    names itself simply not import that one - see `hides` - instead of relying on Kotlin's
    shadowing rules.

    `package_name` overrides the generated package. Two fixtures need a specific package:
    the `library.authors` types a whole-file snippet expects to find beside it, and the
    generated artifact module a spec imports from `io.cratis.arc.generated`.
    """

    types: tuple[str, ...]
    declarations: str
    imports: tuple[str, ...] = ()
    package_name: str = ""


FIXTURES: dict[str, DomainFixture] = {
    # Keep every fixture minimal: it exists to give a fragment the types it references,
    # not to model anything.
    "concepts": DomainFixture(
        types=("AuthorId", "AuthorName", "BookId", "BookTitle"),
        imports=(
            "import io.cratis.arc.concepts.ConceptAs",
            "import java.util.UUID",
        ),
        declarations="""
            data class AuthorId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class AuthorName(private val rawValue: String) : ConceptAs<String> {
                override fun value(): String = rawValue
            }

            data class BookId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class BookTitle(private val rawValue: String) : ConceptAs<String> {
                override fun value(): String = rawValue
            }
        """,
    ),
    "library": DomainFixture(
        types=("Author", "AuthorRepository"),
        imports=(
            f"import {FIXTURE_PACKAGE_ROOT}.concepts.AuthorId",
            f"import {FIXTURE_PACKAGE_ROOT}.concepts.AuthorName",
            "import kotlinx.coroutines.flow.Flow",
        ),
        declarations="""
            data class Author(val id: AuthorId, val name: AuthorName)

            interface AuthorRepository {
                suspend fun save(author: Author)

                suspend fun existsByName(name: AuthorName): Boolean

                suspend fun findById(id: AuthorId): Author?

                fun findAll(): List<Author>

                fun observeAll(): Flow<List<Author>>
            }
        """,
    ),
    "account": DomainFixture(
        types=(
            "AccountId", "AccountHolder", "AccountName", "CustomerId", "Account",
            "AccountRepository", "AccountService",
        ),
        imports=(
            "import io.cratis.arc.concepts.ConceptAs",
            "import java.util.UUID",
        ),
        declarations="""
            data class AccountId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class AccountHolder(private val rawValue: String) : ConceptAs<String> {
                override fun value(): String = rawValue
            }

            data class AccountName(private val rawValue: String) : ConceptAs<String> {
                override fun value(): String = rawValue
            }

            data class CustomerId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class Account(val id: AccountId, val owner: AccountHolder)

            interface AccountRepository {
                suspend fun save(account: Account)
            }

            interface AccountService {
                suspend fun open(id: AccountId, name: AccountName, owner: CustomerId)
            }
        """,
    ),
    "ledger": DomainFixture(
        types=(
            "LedgerId", "AccountId", "LedgerBalance", "AccountBalance", "LedgerSettled",
            "FundsWithdrawn", "MoneyDeposited", "Withdraw",
        ),
        imports=(
            "import io.cratis.arc.artifacts.Command",
            "import io.cratis.arc.artifacts.CommandKey",
            "import io.cratis.arc.concepts.ConceptAs",
            "import java.math.BigDecimal",
            "import java.util.UUID",
        ),
        declarations="""
            data class LedgerId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class AccountId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class LedgerBalance(val balance: BigDecimal)

            data class AccountBalance(val balance: BigDecimal)

            data class LedgerSettled(val balance: BigDecimal)

            data class FundsWithdrawn(val amount: BigDecimal, val remaining: BigDecimal)

            data class MoneyDeposited(val amount: BigDecimal)

            @Command
            data class Withdraw(@CommandKey val accountId: String, val amount: BigDecimal)
        """,
    ),
    "librarycommands": DomainFixture(
        # Only for the validator snippets, which validate a RegisterAuthor they do not
        # declare. The snippets that declare their own RegisterAuthor do not take this
        # fixture, so the name is never declared twice in one compilation unit.
        types=("RegisterAuthor",),
        imports=(
            "import io.cratis.arc.artifacts.Command",
            f"import {FIXTURE_PACKAGE_ROOT}.concepts.AuthorId",
            f"import {FIXTURE_PACKAGE_ROOT}.concepts.AuthorName",
        ),
        declarations="""
            @Command
            data class RegisterAuthor(val id: AuthorId, val name: AuthorName)
        """,
    ),
    "loan": DomainFixture(
        types=("LoanId", "ApplicantId", "CreditScore", "RiskBand", "CreditBureau", "RiskModel", "LoanAssessment"),
        imports=(
            "import io.cratis.arc.concepts.ConceptAs",
            "import java.util.UUID",
        ),
        declarations="""
            data class LoanId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class ApplicantId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class CreditScore(private val rawValue: Int) : ConceptAs<Int> {
                override fun value(): Int = rawValue
            }

            enum class RiskBand {
                LOW,
                MEDIUM,
                HIGH
            }

            interface CreditBureau {
                suspend fun score(applicant: ApplicantId): CreditScore
            }

            interface RiskModel {
                suspend fun band(applicant: ApplicantId): RiskBand
            }

            data class LoanAssessment(val loan: LoanId, val score: CreditScore, val band: RiskBand? = null)
        """,
    ),
    "order": DomainFixture(
        types=("OrderStatus", "OrderView", "OrderViewRepository", "SubmitOrder", "ShippingQuote", "ShippingRates"),
        imports=(
            "import io.cratis.arc.artifacts.Command",
            "import java.math.BigDecimal",
            "import java.util.Optional",
            "import java.util.UUID",
        ),
        declarations="""
            enum class OrderStatus {
                DRAFT,
                READY_FOR_SUBMISSION,
                SUBMITTED
            }

            data class OrderView(
                val id: UUID,
                val status: OrderStatus,
                val destination: String,
                val totalWeight: Double
            )

            interface OrderViewRepository {
                fun findById(id: UUID): Optional<OrderView>
            }

            @Command
            data class SubmitOrder(val id: UUID)

            data class ShippingQuote(val amount: BigDecimal) {
                companion object {
                    val NONE: ShippingQuote = ShippingQuote(BigDecimal.ZERO)
                }
            }

            interface ShippingRates {
                suspend fun quote(destination: String, weight: Double): ShippingQuote
            }
        """,
    ),
    "membership": DomainFixture(
        types=("Member", "MemberRepository"),
        declarations="""
            data class Member(val id: String, val role: String, val name: String)

            interface MemberRepository {
                suspend fun bySubject(subject: String): Member?
            }
        """,
    ),
    "libraryauthorspackage": DomainFixture(
        # The whole-file snippets are written as files in `library.authors` and reference
        # AuthorId/AuthorName without importing them, exactly as a reader's own file would.
        # Emitting the concepts into that package is what makes that true rather than a
        # rewrite of the snippet.
        types=(),
        package_name="library.authors",
        imports=(
            "import io.cratis.arc.concepts.ConceptAs",
            "import java.util.UUID",
        ),
        declarations="""
            data class AuthorId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }

            data class AuthorName(private val rawValue: String) : ConceptAs<String> {
                override fun value(): String = rawValue
            }
        """,
    ),
    "generatedmodule": DomainFixture(
        # The spec snippet imports the module Arc's KSP processor generates for an
        # application. Standing it in here is the only way to compile a snippet that is
        # correct precisely because that type is generated rather than hand-written.
        types=(),
        package_name="io.cratis.arc.generated",
        imports=("import io.cratis.arc.artifacts.ArcArtifactModule",),
        declarations="""
            class LibraryArcArtifactModule : ArcArtifactModule(emptyList(), emptyList())
        """,
    ),
}


@dataclass(frozen=True)
class SnippetContext:
    """The enclosing context a fragment needs in order to compile.

    kind:      "declaration" for top-level declarations (a `@Command` data class, a
               validator) - the prelude and the snippet are emitted side by side in the
               snippet's generated package.
               "member" for a fragment of class members (a bare `provide(...)`, a `@Bean`
               factory, a `@Test`) - emitted inside a generated enclosing class whose
               constructor properties are `host`, so the fragment sees the properties the
               real enclosing command would give it.
               "companion" for a fragment of `@JvmStatic` query methods - emitted inside
               the companion object of a generated class, which is where a real
               `@ReadModel` query method lives.
               "file" for a snippet that is a whole file and declares its own package - it
               is emitted verbatim, with no generated package or imports around it.
    fixtures:  shared domain fixtures supplying the types the snippet references. Every
               type of every listed fixture is imported, except those named in `hides`.
    hides:     fixture type names deliberately not imported because the snippet's own
               prelude declares that name with a different shape.
    imports:   framework imports the fragment omits because the rendered page shows only
               the interesting lines.
    host:      "member" only - the constructor property list of the generated enclosing
               class, naming the properties the fragment reads off `this`.
    prelude:   supporting declarations no fixture supplies.
    """

    kind: str = "declaration"
    fixtures: tuple[str, ...] = ()
    hides: tuple[str, ...] = ()
    imports: tuple[str, ...] = ()
    host: str = ""
    prelude: str = ""


KNOWN_KINDS = ("declaration", "member", "companion", "file")

IMPORT_COMMAND = "import io.cratis.arc.artifacts.Command"
IMPORT_READ_MODEL = "import io.cratis.arc.artifacts.ReadModel"
IMPORT_FROM_SERVICES = "import io.cratis.arc.artifacts.FromServices"
IMPORT_COMMAND_CONTEXT = "import io.cratis.arc.commands.CommandContext"
IMPORT_COMMAND_VALIDATOR = "import io.cratis.arc.commands.CommandValidator"
IMPORT_VALIDATION_RESULT = "import io.cratis.arc.results.ValidationResult"
IMPORT_COMPONENT = "import org.springframework.stereotype.Component"
IMPORT_FLOW = "import kotlinx.coroutines.flow.Flow"
IMPORT_MONGO_QUERY = "import io.cratis.arc.springdata.mongodb.MongoObservableQuery"
IMPORT_MONGO_OBSERVE = "import io.cratis.arc.springdata.mongodb.observe"
IMPORT_CRITERIA = "import org.springframework.data.mongodb.core.query.Criteria"
IMPORT_COMMAND_KEY = "import io.cratis.arc.artifacts.CommandKey"
IMPORT_COMMAND_SCENARIO = "import io.cratis.arc.testing.CommandScenario"
IMPORT_GENERATED_MODULE = "import io.cratis.arc.generated.LibraryArcArtifactModule"
IMPORT_GIVEN_CHRONICLE = "import io.cratis.arc.chronicle.givenChronicle"
IMPORT_BEFORE_EACH = "import org.junit.jupiter.api.BeforeEach"
IMPORT_BIG_DECIMAL = "import java.math.BigDecimal"
IMPORT_UUID = "import java.util.UUID"
IMPORT_CONCEPT_AS = "import io.cratis.arc.concepts.ConceptAs"
IMPORT_CONCEPT_VALIDATOR = "import io.cratis.arc.validation.ConceptValidator"
IMPORT_ROLES = "import io.cratis.arc.authorization.Roles"


# A snippet id is its path under client-snippets without the extension. Every snippet is
# listed: an unlisted snippet would silently compile as a bare declaration fragment, and
# "it compiled because nothing referenced anything" is not a verdict worth having.
SNIPPET_CONTEXTS: dict[str, SnippetContext] = {
    "guides/pipeline-filters/command-filter": SnippetContext(
        imports=(
            IMPORT_COMMAND_CONTEXT,
            "import io.cratis.arc.commands.CommandFilter",
            "import io.cratis.arc.results.CommandResult",
        ),
    ),
    "guides/pipeline-filters/register-command-filter": SnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.commands.CommandFilter",
            "import org.springframework.context.annotation.Bean",
        ),
        prelude="""
            class BillingCommandFilter : CommandFilter {
                override suspend fun execute(context: io.cratis.arc.commands.CommandContext) =
                    io.cratis.arc.results.CommandResult.success(context.correlationId)
            }
        """,
    ),
    "guides/pipeline-filters/query-filter": SnippetContext(
        imports=(
            "import io.cratis.arc.queries.QueryContext",
            "import io.cratis.arc.queries.QueryFilter",
            "import io.cratis.arc.results.QueryResult",
        ),
    ),
    "guides/pipeline-filters/order": SnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.commands.CommandFilter",
            "import org.springframework.context.annotation.Bean",
            "import org.springframework.core.annotation.Order",
        ),
        prelude="""
            class AuditCommandFilter : CommandFilter {
                override suspend fun execute(context: io.cratis.arc.commands.CommandContext) =
                    io.cratis.arc.results.CommandResult.success(context.correlationId)
            }
            class BillingCommandFilter : CommandFilter {
                override suspend fun execute(context: io.cratis.arc.commands.CommandContext) =
                    io.cratis.arc.results.CommandResult.success(context.correlationId)
            }
        """,
    ),
    "guides/typescript-proxies/documented-command": SnippetContext(
        imports=(IMPORT_COMMAND, "import io.cratis.arc.artifacts.CommandKey"),
    ),
    "coming-from-spring-mvc/command-mvc": SnippetContext(
        imports=("import jakarta.validation.Valid",
            "import org.springframework.http.ResponseEntity",
            "import org.springframework.web.bind.annotation.GetMapping",
            "import org.springframework.web.bind.annotation.PostMapping",
            "import org.springframework.web.bind.annotation.RequestBody",
            "import org.springframework.web.bind.annotation.RestController",),
        prelude="""
            data class Task(val id: String, val title: String)
            data class TaskView(val id: String, val title: String)
            data class TaskCreated(val id: String, val title: String)
            data class CreateTaskRequest(val title: String)
            interface TaskRepository {
                fun create(title: String): Task
                fun all(): List<TaskView>
            }
        """,
    ),
    "coming-from-spring-mvc/query-mvc": SnippetContext(
        imports=("import jakarta.validation.Valid",
            "import org.springframework.http.ResponseEntity",
            "import org.springframework.web.bind.annotation.GetMapping",
            "import org.springframework.web.bind.annotation.PostMapping",
            "import org.springframework.web.bind.annotation.RequestBody",
            "import org.springframework.web.bind.annotation.RestController",),
        prelude="""
            data class Task(val id: String, val title: String)
            data class TaskView(val id: String, val title: String)
            data class TaskCreated(val id: String, val title: String)
            data class CreateTaskRequest(val title: String)
            interface TaskRepository {
                fun create(title: String): Task
                fun all(): List<TaskView>
            }
        """,
    ),
    "coming-from-spring-mvc/command-arc": SnippetContext(
        imports=(
            IMPORT_COMMAND,
            "import io.cratis.arc.authorization.AllowAnonymous",
        ),
        prelude="""
            data class Task(val id: String, val title: String)
            data class TaskView(val id: String, val title: String)
            data class TaskCreated(val id: String, val title: String)
            data class CreateTaskRequest(val title: String)
            interface TaskRepository {
                fun create(title: String): Task
                fun all(): List<TaskView>
            }
        """,
    ),
    "coming-from-spring-mvc/query-arc": SnippetContext(
        imports=(
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            "import io.cratis.arc.authorization.AllowAnonymous",
            "import io.cratis.arc.queries.Path",
        ),
        prelude="""
            data class Task(val id: String, val title: String)
            interface TaskRepository {
                fun create(title: String): Task
                fun all(): List<TaskView>
            }
        """,
    ),
    "reference/annotations/exported-type": SnippetContext(
        imports=("import io.cratis.arc.artifacts.ExportedType",),
    ),
    "reference/annotations/registry-outside-spring": SnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.ArcArtifactModule",
            "import io.cratis.arc.artifacts.ArcArtifactModuleRegistry",
            "import io.cratis.arc.json.ArcObjectMapper",
            "import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry",
            "import tools.jackson.databind.ObjectMapper",
        ),
    ),
    "reference/annotations/derived-type-registrar": SnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.polymorphism.DerivedTypeRegistrar",
            "import org.springframework.context.annotation.Bean",
        ),
        prelude="""
            interface Shape
            class ExternalCircle : Shape
        """,
    ),
    "guides/ambient-tenancy/scope": SnippetContext(
        imports=(
            "import io.cratis.arc.tenancy.TenantId",
            "import io.cratis.arc.tenancy.withTenant",
        ),
        prelude="""
            suspend fun performWork() {}
        """,
    ),
    "guides/ambient-tenancy/nested": SnippetContext(
        imports=(
            "import io.cratis.arc.tenancy.TenantId",
            "import io.cratis.arc.tenancy.currentTenant",
            "import io.cratis.arc.tenancy.withTenant",
        ),
    ),
    "guides/spring-data/index/repository-in-query": SnippetContext(
        imports=(
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            "import jakarta.persistence.Entity",
            "import jakarta.persistence.Id",
            "import org.springframework.data.jpa.repository.JpaRepository",
        ),
    ),
    "guides/queries/paths-and-services": SnippetContext(
        imports=(
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            "import io.cratis.arc.authorization.AllowAnonymous",
            "import io.cratis.arc.queries.Path",
        ),
        prelude="""
            interface TaskRepository {
                suspend fun byId(id: String): TaskView?
                fun all(): List<TaskView>
            }
        """,
    ),
    "guides/spring-data/concepts-mongodb/scalar-converters": SnippetContext(
        imports=(
            "import io.cratis.arc.concepts.ConceptAs",
            "import org.springframework.core.convert.converter.Converter",
            "import org.springframework.data.convert.ReadingConverter",
            "import org.springframework.data.convert.WritingConverter",
        ),
        prelude="""
            data class TextValue(private val scalar: String) : ConceptAs<String> {
                override fun value(): String = scalar
            }
        """,
    ),
    "guides/spring-data/concepts-mongodb/register-conversions": SnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.concepts.ConceptAs",
            "import org.springframework.core.convert.converter.Converter",
            "import org.springframework.data.convert.ReadingConverter",
            "import org.springframework.data.convert.WritingConverter",
            "import org.springframework.data.mongodb.core.convert.MongoCustomConversions",
        ),
        prelude="""
            data class TextValue(private val scalar: String) : ConceptAs<String> {
                override fun value(): String = scalar
            }

            @WritingConverter
            class UuidWrite : Converter<java.util.UUID, java.util.UUID> {
                override fun convert(source: java.util.UUID): java.util.UUID = source
            }
            @ReadingConverter
            class UuidRead : Converter<java.util.UUID, java.util.UUID> {
                override fun convert(source: java.util.UUID): java.util.UUID = source
            }
            @WritingConverter
            class TextWrite : Converter<TextValue, String> {
                override fun convert(source: TextValue): String = source.value()
            }
            @ReadingConverter
            class TextRead : Converter<String, TextValue> {
                override fun convert(source: String): TextValue = TextValue(source)
            }
            @WritingConverter
            class LongWrite : Converter<Long, Long> {
                override fun convert(source: Long): Long = source
            }
            @ReadingConverter
            class LongRead : Converter<Long, Long> {
                override fun convert(source: Long): Long = source
            }
        """,
    ),
    "guides/spring-data/concepts-mongodb/programmatic-template": SnippetContext(
        imports=(
            "import com.mongodb.client.MongoClient",
            "import org.springframework.data.mongodb.core.MongoTemplate",
            "import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory",
            "import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver",
            "import org.springframework.data.mongodb.core.convert.MappingMongoConverter",
            "import org.springframework.data.mongodb.core.convert.MongoCustomConversions",
            "import org.springframework.data.mongodb.core.mapping.MongoMappingContext",
            "import org.springframework.data.mongodb.repository.MongoRepository",
            "import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory",
        ),
        prelude="""
            class UuidRow
            class TextRow
            class LongRow
            interface TextRows : MongoRepository<TextRow, String>
        """,
    ),
    "guides/spring-data/concepts-jpa/attribute-converter": SnippetContext(
        imports=(
            "import io.cratis.arc.concepts.ConceptAs",
            "import jakarta.persistence.AttributeConverter",
            "import jakarta.persistence.Converter",
        ),
    ),
    "guides/spring-data/concepts-jpa/embedded-id": SnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.ReadModel",
            "import io.cratis.arc.concepts.ConceptAs",
            "import jakarta.persistence.AttributeConverter",
            "import jakarta.persistence.AttributeOverride",
            "import jakarta.persistence.Column",
            "import jakarta.persistence.Convert",
            "import jakarta.persistence.Converter",
            "import jakarta.persistence.Embeddable",
            "import jakarta.persistence.EmbeddedId",
            "import jakarta.persistence.Entity",
            "import java.io.Serializable",
            "import java.util.UUID",
        ),
        prelude="""
            data class TextValue(private val scalar: String) : ConceptAs<String> {
                override fun value(): String = scalar
            }

            @Converter(autoApply = false)
            class TextConverter : AttributeConverter<TextValue, String> {
                override fun convertToDatabaseColumn(attribute: TextValue?): String? = attribute?.value()
                override fun convertToEntityAttribute(dbData: String?): TextValue? = dbData?.let(::TextValue)
            }
        """,
    ),
    "guides/execution-scopes/contract": SnippetContext(
        imports=(
            IMPORT_COMMAND_CONTEXT,
            "import io.cratis.arc.results.CommandResult",
        ),
    ),
    "guides/execution-scopes/register": SnippetContext(
        imports=(
            IMPORT_COMMAND_CONTEXT,
            IMPORT_COMPONENT,
            "import io.cratis.arc.commands.CommandExecutionScope",
            "import io.cratis.arc.results.CommandResult",
        ),
        prelude="""
            interface UnitOfWork {
                fun begin()
                fun commit()
                fun rollback()
            }
        """,
    ),
    "reference/configuration/configure-object-mapper": SnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.json.ArcObjectMapper",
            "import tools.jackson.databind.json.JsonMapper",
        ),
    ),
    "guides/command-keys/declared-key": SnippetContext(
        imports=(
            IMPORT_COMMAND,
            "import io.cratis.arc.artifacts.CommandKey",
            IMPORT_FROM_SERVICES,
            "import io.cratis.arc.concepts.ConceptAs",
            "import java.util.UUID",
        ),
        prelude="""
            data class OrderId(private val rawValue: UUID) : ConceptAs<UUID> {
                override fun value(): UUID = rawValue
            }
            data class Address(val line: String, val postcode: String)
            interface OrderRepository {
                suspend fun updateAddress(orderId: OrderId, address: Address)
            }
        """,
    ),
    "guides/command-keys/computed-key": SnippetContext(
        imports=(
            IMPORT_COMMAND,
            "import io.cratis.arc.commands.CommandKeyProvider",
            IMPORT_FROM_SERVICES,
        ),
        prelude="""
            interface ArchiveService {
                suspend fun run(tenant: String, year: Int)
            }
        """,
    ),
    "guides/read-model-naming/naming-policy": SnippetContext(
        prelude="",
    ),
    "guides/read-model-naming/default-policy": SnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.naming.NamingPolicy",
            "import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy",
        ),
    ),
    "guides/read-model-naming/override-policy": SnippetContext(
        imports=(
            "import io.cratis.arc.naming.NamingPolicy",
            "import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy",
            "import org.springframework.context.annotation.Bean",
            "import org.springframework.context.annotation.Configuration",
        ),
        prelude="""
            class PersonView(val id: String)
        """,
    ),
    "scenarios/provide-data-to-a-command/assess-loan": SnippetContext(
        kind="declaration",
        fixtures=("loan",),
        imports=(IMPORT_COMMAND,),
    ),
    "scenarios/provide-data-to-a-command/cancellation": SnippetContext(
        kind="declaration",
        fixtures=("loan",),
        imports=(
            IMPORT_COMMAND,
            "import kotlinx.coroutines.currentCoroutineContext",
            "import kotlinx.coroutines.ensureActive",
        ),
    ),
    "scenarios/provide-data-to-a-command/provider-owned-state": SnippetContext(
        kind="member",
        fixtures=("order",),
    ),
    "scenarios/provide-data-to-a-command/several-values": SnippetContext(
        kind="member",
        fixtures=("loan",),
        host="val loanId: LoanId, val applicant: ApplicantId",
    ),
    "scenarios/provide-data-to-a-command/short-circuit": SnippetContext(
        kind="member",
        fixtures=("loan",),
        # The snippet's whole point is a lookup that can come back empty, so its bureau
        # returns a nullable score. Hiding the shared CreditBureau and declaring the
        # nullable one in the prelude keeps the shared fixture honest for every other
        # snippet - an elvis on a non-null value is a Kotlin warning, and this build
        # treats warnings as errors.
        hides=("CreditBureau",),
        imports=(IMPORT_VALIDATION_RESULT,),
        host="val loanId: LoanId, val applicant: ApplicantId",
        prelude="""
            interface CreditBureau {
                suspend fun score(applicant: ApplicantId): CreditScore?
            }
        """,
    ),
    "scenarios/provide-data-to-a-command/test-the-decision": SnippetContext(
        kind="member",
        fixtures=("loan",),
        imports=(
            IMPORT_COMMAND,
            "import java.util.UUID",
            "import kotlin.test.assertEquals",
            "import org.junit.jupiter.api.Test",
        ),
        prelude="""
            @Command
            data class AssessLoan(val loanId: LoanId, val applicant: ApplicantId) {
                fun handle(creditScore: CreditScore): LoanAssessment = LoanAssessment(loanId, creditScore)
            }
        """,
    ),
    "scenarios/query-related-data/books-for-author": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            IMPORT_FLOW,
            IMPORT_MONGO_QUERY,
            IMPORT_MONGO_OBSERVE,
            IMPORT_CRITERIA,
        ),
    ),
    "scenarios/return-a-result-or-error/handle-result": SnippetContext(
        kind="member",
        fixtures=("concepts", "library"),
        imports=(
            IMPORT_VALIDATION_RESULT,
            "import io.cratis.arc.validation.ValidationFailure",
        ),
        host="val id: AuthorId, val name: AuthorName",
    ),
    "scenarios/test-a-command/command-under-test": SnippetContext(
        kind="file",
        fixtures=("libraryauthorspackage",),
    ),
    "scenarios/test-a-command/spec": SnippetContext(
        # Compiles against the `library.authors` types declared by the command-under-test
        # snippet it is rendered next to, in the same compilation.
        kind="file",
        fixtures=("libraryauthorspackage", "generatedmodule"),
    ),
    "scenarios/validate-a-command/command-rule": SnippetContext(
        kind="declaration",
        fixtures=("concepts", "librarycommands"),
        imports=(IMPORT_COMPONENT, IMPORT_COMMAND_VALIDATOR, IMPORT_COMMAND_CONTEXT, IMPORT_VALIDATION_RESULT),
    ),
    "scenarios/validate-a-command/concept-rule": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(
            IMPORT_COMPONENT,
            "import io.cratis.arc.validation.ConceptValidator",
            IMPORT_VALIDATION_RESULT,
        ),
    ),
    "scenarios/validate-a-command/service-rule": SnippetContext(
        kind="declaration",
        fixtures=("concepts", "librarycommands"),
        imports=(IMPORT_COMPONENT, IMPORT_COMMAND_VALIDATOR, IMPORT_COMMAND_CONTEXT, IMPORT_VALIDATION_RESULT),
    ),
    "scenarios/validate-a-command/state-rule": SnippetContext(
        kind="declaration",
        fixtures=("order",),
        imports=(IMPORT_COMPONENT, IMPORT_COMMAND_VALIDATOR, IMPORT_COMMAND_CONTEXT, IMPORT_VALIDATION_RESULT),
    ),
    "scenarios/use-current-state-in-a-command/rename-author": SnippetContext(
        kind="declaration",
        fixtures=("concepts", "library"),
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES),
    ),
    "scenarios/use-current-state-in-a-command/rename-author-validator": SnippetContext(
        kind="declaration",
        fixtures=("concepts", "library"),
        imports=(
            IMPORT_COMMAND,
            IMPORT_COMMAND_KEY,
            IMPORT_COMPONENT,
            IMPORT_COMMAND_VALIDATOR,
            IMPORT_COMMAND_CONTEXT,
            IMPORT_VALIDATION_RESULT,
        ),
        prelude="""
            @Command
            data class RenameAuthor(@CommandKey val id: AuthorId, val newName: AuthorName)
        """,
    ),
    "scenarios/use-current-state-in-a-command/register-customer-validator": SnippetContext(
        kind="declaration",
        imports=(
            IMPORT_COMMAND,
            IMPORT_COMMAND_KEY,
            IMPORT_COMPONENT,
            IMPORT_COMMAND_VALIDATOR,
            IMPORT_COMMAND_CONTEXT,
            IMPORT_VALIDATION_RESULT,
            IMPORT_UUID,
        ),
        prelude="""
            data class Customer(val id: UUID, val name: String)

            interface CustomerRepository {
                suspend fun findById(id: UUID): Customer?
            }

            @Command
            data class RegisterCustomer(@CommandKey val id: UUID, val name: String)
        """,
    ),
    "scenarios/use-current-state-in-a-command/required-order-state": SnippetContext(
        kind="declaration",
        fixtures=("order",),
        # The snippet's whole point is the command declaring its own required read-model
        # parameter, so it declares SubmitOrder rather than borrowing the fixture's.
        hides=("SubmitOrder",),
        imports=(IMPORT_COMMAND, IMPORT_COMMAND_KEY, IMPORT_FROM_SERVICES, IMPORT_UUID),
        prelude="""
            interface OrderSubmissions {
                suspend fun submit(id: UUID)
            }
        """,
    ),
    "scenarios/use-current-state-in-a-command/chronicle-commands": SnippetContext(
        kind="declaration",
        fixtures=("ledger",),
        hides=("Withdraw",),
        imports=(IMPORT_COMMAND, IMPORT_COMMAND_KEY, IMPORT_BIG_DECIMAL),
    ),
    "scenarios/use-current-state-in-a-command/seed-events": SnippetContext(
        # The generated enclosing class stands in for the test class the fragment is a
        # member of; the prelude supplies the scenario and event source a real test declares.
        kind="member",
        fixtures=("ledger", "generatedmodule"),
        imports=(
            IMPORT_BEFORE_EACH,
            IMPORT_BIG_DECIMAL,
            IMPORT_COMMAND_SCENARIO,
            IMPORT_GENERATED_MODULE,
            IMPORT_GIVEN_CHRONICLE,
        ),
        prelude="""
            private val scenario = CommandScenario(LibraryArcArtifactModule(), Withdraw::class.java)
            private val accountId = "account-42"
        """,
    ),
    "scenarios/use-current-state-in-a-command/pin-read-model": SnippetContext(
        kind="member",
        fixtures=("ledger", "generatedmodule"),
        imports=(
            IMPORT_BEFORE_EACH,
            IMPORT_BIG_DECIMAL,
            IMPORT_COMMAND_SCENARIO,
            IMPORT_GENERATED_MODULE,
        ),
        prelude="""
            private val scenario = CommandScenario(LibraryArcArtifactModule(), Withdraw::class.java)
            private val accountId = "account-42"
        """,
    ),
    "frontend/index/open-account": SnippetContext(
        kind="declaration",
        fixtures=("account",),
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES),
    ),
    "frontend/react/proxy-generation/open-debit-account": SnippetContext(
        # Deliberately self-contained: the page teaches what a whole backend file looks like
        # before the generator turns it into TypeScript.
        kind="declaration",
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES),
    ),
    "frontend/react/commands/index/command-payload": SnippetContext(
        kind="declaration",
        fixtures=("account",),
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES),
    ),
    "frontend/react/queries/usage/parameterized-query": SnippetContext(
        kind="declaration",
        fixtures=("account",),
        imports=(IMPORT_READ_MODEL, IMPORT_FROM_SERVICES),
        prelude="""
            interface DebitAccountRepository {
                fun findByNameStartingWith(prefix: String): List<DebitAccount>
            }
        """,
    ),
    "frontend/react/command-form/validation/profile-command": SnippetContext(
        kind="file",
    ),
    "frontend/react/command-form/auto-server-validation/server-only-rule": SnippetContext(
        # A replacement for the validator the validation page declares, so it stays a fragment
        # rather than a second file in the same package.
        kind="declaration",
        imports=(
            IMPORT_COMMAND,
            IMPORT_COMPONENT,
            IMPORT_COMMAND_VALIDATOR,
            IMPORT_COMMAND_CONTEXT,
            IMPORT_VALIDATION_RESULT,
        ),
        prelude="""
            @Command
            data class UpdateProfile(val name: String, val email: String)
        """,
    ),
    "tutorial/first-slice/author-slice": SnippetContext(
        # The chapter's own Author is the read model the reader declares, so the snippet
        # declares it and the prelude supplies only the repository it saves through.
        kind="declaration",
        fixtures=("concepts",),
        imports=(
            IMPORT_COMMAND,
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            IMPORT_FLOW,
            IMPORT_MONGO_QUERY,
            IMPORT_MONGO_OBSERVE,
        ),
        prelude="""
            interface AuthorRepository {
                suspend fun save(author: Author)
            }
        """,
    ),
    "tutorial/validation/author-name-rule": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(IMPORT_COMPONENT, IMPORT_CONCEPT_VALIDATOR, IMPORT_VALIDATION_RESULT),
    ),
    "tutorial/validation/duplicate-name-rule": SnippetContext(
        kind="declaration",
        fixtures=("concepts", "library", "librarycommands"),
        imports=(IMPORT_COMPONENT, IMPORT_COMMAND_VALIDATOR, IMPORT_COMMAND_CONTEXT, IMPORT_VALIDATION_RESULT),
    ),
    "tutorial/books-and-relationships/book-concepts": SnippetContext(
        # Deliberately fixture-free: the chapter is teaching the reader to declare these
        # two concepts, so importing the fixture's BookId would collide with the snippet.
        kind="declaration",
        imports=(IMPORT_CONCEPT_AS, IMPORT_UUID),
    ),
    "tutorial/books-and-relationships/add-book": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES),
        prelude="""
            data class Book(val id: BookId, val authorId: AuthorId, val title: BookTitle)

            interface BookRepository {
                suspend fun save(book: Book)
            }
        """,
    ),
    "tutorial/books-and-relationships/books-for-author": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            IMPORT_FLOW,
            IMPORT_MONGO_QUERY,
            IMPORT_MONGO_OBSERVE,
            IMPORT_CRITERIA,
        ),
    ),
    "tutorial/authorization/roles-on-command": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES, IMPORT_ROLES),
        prelude="""
            data class Author(val id: AuthorId, val name: AuthorName)

            interface AuthorRepository {
                suspend fun save(author: Author)
            }
        """,
    ),
    "tutorial/authorization/roles-on-query": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            IMPORT_FLOW,
            IMPORT_MONGO_QUERY,
            IMPORT_MONGO_OBSERVE,
            IMPORT_ROLES,
        ),
    ),
    "tutorial/real-time/observable-query": SnippetContext(
        kind="companion",
        fixtures=("concepts", "library"),
        imports=(IMPORT_FROM_SERVICES, IMPORT_FLOW, IMPORT_MONGO_QUERY, IMPORT_MONGO_OBSERVE),
    ),
    "tutorial/real-time/one-shot-query": SnippetContext(
        kind="companion",
        fixtures=("concepts", "library"),
        imports=(IMPORT_FROM_SERVICES,),
    ),
    "understanding-identity-and-access/authorization": SnippetContext(
        kind="declaration",
        fixtures=("concepts",),
        imports=(
            IMPORT_COMMAND,
            IMPORT_READ_MODEL,
            IMPORT_FROM_SERVICES,
            IMPORT_FLOW,
            "import io.cratis.arc.authorization.AllowAnonymous",
            "import io.cratis.arc.authorization.Roles",
        ),
        # This snippet declares its own Author, so it cannot use the shared library
        # fixture's repository - that one stores the shared Author.
        prelude="""
            interface AuthorRepository {
                suspend fun save(author: Author)

                fun observeAll(): Flow<List<Author>>
            }
        """,
    ),
    "understanding-identity-and-access/identity-provider": SnippetContext(
        kind="member",
        fixtures=("membership",),
        imports=(
            "import io.cratis.arc.identity.IdentityDetails",
            "import io.cratis.arc.identity.IdentityDetailsProvider",
            "import io.cratis.arc.identity.IdentityProviderContext",
            "import org.springframework.context.annotation.Bean",
        ),
    ),
    "understanding-the-proxy-boundary/register-author": SnippetContext(
        kind="declaration",
        fixtures=("concepts", "library"),
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES),
    ),
    "understanding-the-proxy-boundary/rename-property": SnippetContext(
        kind="declaration",
        fixtures=("concepts", "library"),
        imports=(IMPORT_COMMAND, IMPORT_FROM_SERVICES),
    ),
}


# ── Java ──────────────────────────────────────────────────────────────────────────────

JAVA_IMPORT_COMMAND = "import io.cratis.arc.artifacts.Command;"
JAVA_IMPORT_COMMAND_KEY = "import io.cratis.arc.artifacts.CommandKey;"
JAVA_IMPORT_READ_MODEL = "import io.cratis.arc.artifacts.ReadModel;"
JAVA_IMPORT_FROM_SERVICES = "import io.cratis.arc.artifacts.FromServices;"
JAVA_IMPORT_COMMAND_CONTEXT = "import io.cratis.arc.commands.CommandContext;"
JAVA_IMPORT_COMMAND_VALIDATOR = "import io.cratis.arc.commands.CommandValidator;"
JAVA_IMPORT_PROVIDED_VALUES = "import io.cratis.arc.commands.CommandProvidedValues;"
JAVA_IMPORT_BLOCKING_VALIDATOR = "import io.cratis.arc.java.BlockingCommandValidator;"
JAVA_IMPORT_BLOCKING_VALIDATOR_ADAPTER = "import io.cratis.arc.java.BlockingCommandValidatorAdapter;"
JAVA_IMPORT_ASYNC_VALIDATOR = "import io.cratis.arc.java.AsyncCommandValidator;"
JAVA_IMPORT_ASYNC_VALIDATOR_ADAPTER = "import io.cratis.arc.java.AsyncCommandValidatorAdapter;"
JAVA_IMPORT_CONCEPT_AS = "import io.cratis.arc.concepts.ConceptAs;"
JAVA_IMPORT_CONCEPT_VALIDATOR = "import io.cratis.arc.validation.ConceptValidator;"
JAVA_IMPORT_VALIDATION_FAILURE = "import io.cratis.arc.validation.ValidationFailure;"
JAVA_IMPORT_VALIDATION_RESULT = "import io.cratis.arc.results.ValidationResult;"
JAVA_IMPORT_ROLES = "import io.cratis.arc.authorization.Roles;"
JAVA_IMPORT_ALLOW_ANONYMOUS = "import io.cratis.arc.authorization.AllowAnonymous;"
JAVA_IMPORT_IDENTITY_DETAILS = "import io.cratis.arc.identity.IdentityDetails;"
JAVA_IMPORT_IDENTITY_DETAILS_PROVIDER = "import io.cratis.arc.identity.IdentityDetailsProvider;"
JAVA_IMPORT_ASYNC_IDENTITY_PROVIDER = "import io.cratis.arc.identity.AsyncIdentityDetailsProvider;"
JAVA_IMPORT_ASYNC_IDENTITY_ADAPTER = "import io.cratis.arc.identity.AsyncIdentityDetailsProviderAdapter;"
JAVA_IMPORT_IDENTITY_CONTEXT = "import io.cratis.arc.identity.IdentityProviderContext;"
JAVA_IMPORT_MONGO_QUERY = "import io.cratis.arc.springdata.mongodb.MongoObservableQuery;"
JAVA_IMPORT_MONGO_OBSERVATIONS = "import io.cratis.arc.springdata.mongodb.MongoObservations;"
JAVA_IMPORT_CRITERIA = "import org.springframework.data.mongodb.core.query.Criteria;"
JAVA_IMPORT_COMPONENT = "import org.springframework.stereotype.Component;"
JAVA_IMPORT_CONFIGURATION = "import org.springframework.context.annotation.Configuration;"
JAVA_IMPORT_BEAN = "import org.springframework.context.annotation.Bean;"
JAVA_IMPORT_COMMAND_SCENARIO = "import io.cratis.arc.testing.CommandScenario;"
JAVA_IMPORT_CHRONICLE_SCENARIOS = "import io.cratis.arc.chronicle.ChronicleCommandScenarios;"
JAVA_IMPORT_GENERATED_MODULE = (
    f"import {JAVA_FIXTURE_PACKAGE}.Fixture_generatedmodule.LibraryArcArtifactModule;")
JAVA_IMPORT_TEST = "import org.junit.jupiter.api.Test;"
JAVA_IMPORT_BEFORE_EACH = "import org.junit.jupiter.api.BeforeEach;"
JAVA_IMPORT_ASSERT_EQUALS = "import static org.junit.jupiter.api.Assertions.assertEquals;"
JAVA_IMPORT_ARRAY_LIST = "import java.util.ArrayList;"
JAVA_IMPORT_LIST = "import java.util.List;"
JAVA_IMPORT_OPTIONAL = "import java.util.Optional;"
JAVA_IMPORT_UUID = "import java.util.UUID;"
JAVA_IMPORT_BIG_DECIMAL = "import java.math.BigDecimal;"
JAVA_IMPORT_FLOW = "import java.util.concurrent.Flow;"
JAVA_IMPORT_COMPLETION_STAGE = "import java.util.concurrent.CompletionStage;"
JAVA_IMPORT_COMPLETABLE_FUTURE = "import java.util.concurrent.CompletableFuture;"


@dataclass(frozen=True)
class JavaFixture:
    """Supporting Java domain types shared by the snippets of one domain.

    Emitted as one `public interface Fixture_<name>` per domain, in a single fixtures
    package. The wrapper is what lets two fixtures both declare `AccountId` without
    colliding - in Java a nested type is addressed through its enclosing type - and every
    member of an interface is implicitly public and static, so the declarations read as
    ordinary top-level Java.
    """

    types: tuple[str, ...]
    declarations: str
    imports: tuple[str, ...] = ()


JAVA_FIXTURES: dict[str, JavaFixture] = {
    # Keep every fixture minimal: it exists to give a fragment the types it references,
    # not to model anything. Repository and service methods are blocking, because a Java
    # Spring Data repository is; the asynchronous shapes appear only where a snippet is
    # teaching asynchrony.
    "concepts": JavaFixture(
        types=("AuthorId", "AuthorName", "BookId", "BookTitle"),
        imports=(JAVA_IMPORT_CONCEPT_AS, JAVA_IMPORT_UUID),
        declarations="""
            public record AuthorId(UUID value) implements ConceptAs<UUID> {
            }

            public record AuthorName(String value) implements ConceptAs<String> {
            }

            public record BookId(UUID value) implements ConceptAs<UUID> {
            }

            public record BookTitle(String value) implements ConceptAs<String> {
            }
        """,
    ),
    "library": JavaFixture(
        types=("Author", "AuthorRepository"),
        imports=(
            f"import {JAVA_FIXTURE_PACKAGE}.Fixture_concepts.AuthorId;",
            f"import {JAVA_FIXTURE_PACKAGE}.Fixture_concepts.AuthorName;",
            JAVA_IMPORT_LIST,
            JAVA_IMPORT_FLOW,
        ),
        declarations="""
            public record Author(AuthorId id, AuthorName name) {
            }

            public interface AuthorRepository {
                void save(Author author);

                boolean existsByName(AuthorName name);

                Author findById(AuthorId id);

                List<Author> findAll();

                Flow.Publisher<List<Author>> observeAll();
            }
        """,
    ),
    "account": JavaFixture(
        types=(
            "AccountId", "AccountHolder", "AccountName", "CustomerId", "Account",
            "AccountRepository", "AccountService",
        ),
        imports=(JAVA_IMPORT_CONCEPT_AS, JAVA_IMPORT_UUID),
        declarations="""
            public record AccountId(UUID value) implements ConceptAs<UUID> {
            }

            public record AccountHolder(String value) implements ConceptAs<String> {
            }

            public record AccountName(String value) implements ConceptAs<String> {
            }

            public record CustomerId(UUID value) implements ConceptAs<UUID> {
            }

            public record Account(AccountId id, AccountHolder owner) {
            }

            public interface AccountRepository {
                void save(Account account);
            }

            public interface AccountService {
                void open(AccountId id, AccountName name, CustomerId owner);
            }
        """,
    ),
    "ledger": JavaFixture(
        types=(
            "LedgerId", "AccountId", "LedgerBalance", "AccountBalance", "LedgerSettled",
            "FundsWithdrawn", "MoneyDeposited", "Withdraw",
        ),
        imports=(
            JAVA_IMPORT_COMMAND,
            JAVA_IMPORT_COMMAND_KEY,
            JAVA_IMPORT_CONCEPT_AS,
            JAVA_IMPORT_BIG_DECIMAL,
            JAVA_IMPORT_UUID,
        ),
        declarations="""
            public record LedgerId(UUID value) implements ConceptAs<UUID> {
            }

            public record AccountId(UUID value) implements ConceptAs<UUID> {
            }

            public record LedgerBalance(BigDecimal balance) {
            }

            public record AccountBalance(BigDecimal balance) {
            }

            public record LedgerSettled(BigDecimal balance) {
            }

            public record FundsWithdrawn(BigDecimal amount, BigDecimal remaining) {
            }

            public record MoneyDeposited(BigDecimal amount) {
            }

            @Command
            public record Withdraw(@CommandKey String accountId, BigDecimal amount) {
            }
        """,
    ),
    "librarycommands": JavaFixture(
        # Only for the validator snippets, which validate a RegisterAuthor they do not
        # declare. The snippets that declare their own RegisterAuthor do not take this
        # fixture, so the name is never declared twice in one wrapper.
        types=("RegisterAuthor",),
        imports=(
            JAVA_IMPORT_COMMAND,
            f"import {JAVA_FIXTURE_PACKAGE}.Fixture_concepts.AuthorId;",
            f"import {JAVA_FIXTURE_PACKAGE}.Fixture_concepts.AuthorName;",
        ),
        declarations="""
            @Command
            public record RegisterAuthor(AuthorId id, AuthorName name) {
            }
        """,
    ),
    "loan": JavaFixture(
        types=("LoanId", "ApplicantId", "CreditScore", "RiskBand", "CreditBureau", "RiskModel", "LoanAssessment"),
        imports=(JAVA_IMPORT_CONCEPT_AS, JAVA_IMPORT_COMPLETION_STAGE, JAVA_IMPORT_UUID),
        declarations="""
            public record LoanId(UUID value) implements ConceptAs<UUID> {
            }

            public record ApplicantId(UUID value) implements ConceptAs<UUID> {
            }

            public record CreditScore(Integer value) implements ConceptAs<Integer> {
            }

            public enum RiskBand {
                LOW,
                MEDIUM,
                HIGH
            }

            public interface CreditBureau {
                CreditScore score(ApplicantId applicant);

                CompletionStage<CreditScore> scoreAsync(ApplicantId applicant);
            }

            public interface RiskModel {
                RiskBand band(ApplicantId applicant);
            }

            public record LoanAssessment(LoanId loan, CreditScore score, RiskBand band) {
                public LoanAssessment(LoanId loan, CreditScore score) {
                    this(loan, score, null);
                }
            }
        """,
    ),
    "order": JavaFixture(
        types=("OrderStatus", "OrderView", "OrderViewRepository", "SubmitOrder", "ShippingQuote", "ShippingRates"),
        imports=(
            JAVA_IMPORT_COMMAND,
            JAVA_IMPORT_BIG_DECIMAL,
            JAVA_IMPORT_OPTIONAL,
            JAVA_IMPORT_UUID,
        ),
        declarations="""
            public enum OrderStatus {
                DRAFT,
                READY_FOR_SUBMISSION,
                SUBMITTED
            }

            public record OrderView(UUID id, OrderStatus status, String destination, double totalWeight) {
            }

            public interface OrderViewRepository {
                Optional<OrderView> findById(UUID id);
            }

            @Command
            public record SubmitOrder(UUID id) {
            }

            public record ShippingQuote(BigDecimal amount) {
                public static final ShippingQuote NONE = new ShippingQuote(BigDecimal.ZERO);
            }

            public interface ShippingRates {
                ShippingQuote quote(String destination, double weight);
            }
        """,
    ),
    "membership": JavaFixture(
        types=("Member", "MemberRepository"),
        declarations="""
            public record Member(String id, String role, String name) {
            }

            public interface MemberRepository {
                Member bySubject(String subject);
            }
        """,
    ),
    "generatedmodule": JavaFixture(
        # The spec snippets construct the module Arc's KSP processor generates for an
        # application. Standing it in here is the only way to compile a snippet that is
        # correct precisely because that type is generated rather than hand-written. It
        # deliberately does not reuse the Kotlin fixture's `io.cratis.arc.generated`
        # package: both languages compile into one source set, and two declarations of the
        # same fully qualified name would collide.
        types=(),
        imports=("import io.cratis.arc.artifacts.ArcArtifactModule;", JAVA_IMPORT_LIST),
        declarations="""
            public final class LibraryArcArtifactModule extends ArcArtifactModule {
                public LibraryArcArtifactModule() {
                    super(List.of(), List.of());
                }
            }
        """,
    ),
}


@dataclass(frozen=True)
class JavaSnippetContext:
    """The enclosing context a Java fragment needs in order to compile.

    kind:      "declaration" for top-level declarations (a `@Command` record, a validator) -
               the prelude and the snippet become members of the generated wrapper
               interface, where they are implicitly public and static.
               "member" for a fragment of class members (a bare `provide(...)`, a `@Bean`
               factory, a `@Test`) - emitted inside a generated `SnippetHost` class whose
               fields are `host`, so the fragment sees what the real enclosing type would
               give it. Java static query methods are members too; there is no Kotlin
               `companion` equivalent to model.
    fixtures:  shared domain fixtures supplying the types the snippet references. Every
               type of every listed fixture is imported, except those named in `hides`.
    hides:     fixture type names deliberately not imported because the snippet declares
               that name itself.
    imports:   framework imports the fragment omits because the rendered page shows only
               the interesting lines.
    host:      "member" only - the field declarations of the generated enclosing class.
    prelude:   supporting declarations no fixture supplies.
    """

    kind: str = "declaration"
    fixtures: tuple[str, ...] = ()
    hides: tuple[str, ...] = ()
    imports: tuple[str, ...] = ()
    host: str = ""
    prelude: str = ""


JAVA_KNOWN_KINDS = ("declaration", "member")

# Registering a Java command validator takes two parts - an ordinary class implementing the
# Blocking/Async surface, and the adapter published as a `CommandValidator` bean - so those
# snippets carry a small `@Configuration` and need its imports.
JAVA_BLOCKING_VALIDATOR_IMPORTS = (
    JAVA_IMPORT_BLOCKING_VALIDATOR,
    JAVA_IMPORT_BLOCKING_VALIDATOR_ADAPTER,
    JAVA_IMPORT_COMMAND_CONTEXT,
    JAVA_IMPORT_COMMAND_VALIDATOR,
    JAVA_IMPORT_VALIDATION_RESULT,
    JAVA_IMPORT_CONFIGURATION,
    JAVA_IMPORT_BEAN,
    JAVA_IMPORT_LIST,
)
JAVA_ASYNC_VALIDATOR_IMPORTS = (
    JAVA_IMPORT_ASYNC_VALIDATOR,
    JAVA_IMPORT_ASYNC_VALIDATOR_ADAPTER,
    JAVA_IMPORT_COMMAND_CONTEXT,
    JAVA_IMPORT_COMMAND_VALIDATOR,
    JAVA_IMPORT_VALIDATION_RESULT,
    JAVA_IMPORT_CONFIGURATION,
    JAVA_IMPORT_BEAN,
    JAVA_IMPORT_LIST,
    JAVA_IMPORT_COMPLETION_STAGE,
)
JAVA_OBSERVABLE_QUERY_IMPORTS = (
    JAVA_IMPORT_READ_MODEL,
    JAVA_IMPORT_FROM_SERVICES,
    JAVA_IMPORT_MONGO_QUERY,
    JAVA_IMPORT_FLOW,
    JAVA_IMPORT_LIST,
)
JAVA_SCENARIO_HOST = """
    private final CommandScenario<Withdraw> scenario =
        new CommandScenario<>(new LibraryArcArtifactModule(), Withdraw.class);
    private final String accountId = "account-42";
"""

# A snippet id is its path under client-snippets-java without the extension. Every snippet
# is listed for the same reason the Kotlin table lists every snippet: an unlisted snippet
# would silently compile as a bare fragment, and "it compiled because nothing referenced
# anything" is not a verdict worth having.
JAVA_SNIPPET_CONTEXTS: dict[str, JavaSnippetContext] = {
    "guides/pipeline-filters/command-filter": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.commands.CommandContext;",
            "import io.cratis.arc.java.BlockingCommandFilter;",
            "import io.cratis.arc.results.CommandResult;",
        ),
    ),
    "guides/pipeline-filters/register-command-filter": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.commands.CommandContext;",
            "import io.cratis.arc.commands.CommandFilter;",
            "import io.cratis.arc.java.BlockingCommandFilter;",
            "import io.cratis.arc.java.BlockingCommandFilterAdapter;",
            "import io.cratis.arc.results.CommandResult;",
            "import org.springframework.context.annotation.Bean;",
        ),
        prelude="""
            final class BillingCommandFilter implements BlockingCommandFilter {
                @Override
                public CommandResult<?> execute(CommandContext context) {
                    return CommandResult.success(context.getCorrelationId());
                }
            }
        """,
    ),
    "guides/pipeline-filters/query-filter": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.java.BlockingQueryFilter;",
            "import io.cratis.arc.queries.QueryContext;",
            "import io.cratis.arc.results.QueryResult;",
        ),
    ),
    "guides/pipeline-filters/order": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.commands.CommandContext;",
            "import io.cratis.arc.commands.CommandFilter;",
            "import io.cratis.arc.java.BlockingCommandFilter;",
            "import io.cratis.arc.java.BlockingCommandFilterAdapter;",
            "import io.cratis.arc.results.CommandResult;",
            "import org.springframework.context.annotation.Bean;",
            "import org.springframework.core.annotation.Order;",
        ),
        prelude="""
            final class AuditCommandFilter implements BlockingCommandFilter {
                @Override
                public CommandResult<?> execute(CommandContext context) {
                    return CommandResult.success(context.getCorrelationId());
                }
            }
            final class BillingCommandFilter implements BlockingCommandFilter {
                @Override
                public CommandResult<?> execute(CommandContext context) {
                    return CommandResult.success(context.getCorrelationId());
                }
            }
        """,
    ),
    "guides/typescript-proxies/documented-command": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.Command;",
            "import io.cratis.arc.artifacts.CommandKey;",
        ),
    ),
    "coming-from-spring-mvc/command-mvc": JavaSnippetContext(
        imports=("import jakarta.validation.Valid;",
            "import java.util.List;",
            "import org.springframework.http.ResponseEntity;",
            "import org.springframework.web.bind.annotation.GetMapping;",
            "import org.springframework.web.bind.annotation.PostMapping;",
            "import org.springframework.web.bind.annotation.RequestBody;",
            "import org.springframework.web.bind.annotation.RestController;",),
        prelude="""
            record Task(String id, String title) { }
            record TaskView(String id, String title) { }
            record TaskCreated(String id, String title) { }
            record CreateTaskRequest(String title) { }
            interface TaskRepository {
                Task create(String title);
                java.util.List<TaskView> all();
            }
        """,
    ),
    "coming-from-spring-mvc/query-mvc": JavaSnippetContext(
        imports=("import jakarta.validation.Valid;",
            "import java.util.List;",
            "import org.springframework.http.ResponseEntity;",
            "import org.springframework.web.bind.annotation.GetMapping;",
            "import org.springframework.web.bind.annotation.PostMapping;",
            "import org.springframework.web.bind.annotation.RequestBody;",
            "import org.springframework.web.bind.annotation.RestController;",),
        prelude="""
            record Task(String id, String title) { }
            record TaskView(String id, String title) { }
            record TaskCreated(String id, String title) { }
            record CreateTaskRequest(String title) { }
            interface TaskRepository {
                Task create(String title);
                java.util.List<TaskView> all();
            }
        """,
    ),
    "coming-from-spring-mvc/command-arc": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.Command;",
            "import io.cratis.arc.authorization.AllowAnonymous;",
        ),
        prelude="""
            record Task(String id, String title) { }
            record TaskView(String id, String title) { }
            record TaskCreated(String id, String title) { }
            record CreateTaskRequest(String title) { }
            interface TaskRepository {
                Task create(String title);
                java.util.List<TaskView> all();
            }
        """,
    ),
    "coming-from-spring-mvc/query-arc": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.FromServices;",
            "import io.cratis.arc.artifacts.ReadModel;",
            "import io.cratis.arc.authorization.AllowAnonymous;",
            "import io.cratis.arc.queries.Path;",
            "import java.util.List;",
        ),
        prelude="""
            record Task(String id, String title) { }
            interface TaskRepository {
                Task create(String title);
                List<TaskView> all();
            }
        """,
    ),
    "reference/annotations/exported-type": JavaSnippetContext(
        imports=("import io.cratis.arc.artifacts.ExportedType;",),
    ),
    "reference/annotations/registry-outside-spring": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.ArcArtifactModule;",
            "import io.cratis.arc.artifacts.ArcArtifactModuleRegistry;",
            "import io.cratis.arc.json.ArcObjectMapper;",
            "import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry;",
            "import tools.jackson.databind.ObjectMapper;",
        ),
    ),
    "reference/annotations/derived-type-registrar": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.polymorphism.DerivedTypeRegistrar;",
            "import org.springframework.context.annotation.Bean;",
        ),
        prelude="""
            interface Shape { }
            class ExternalCircle implements Shape { }
        """,
    ),
    "guides/ambient-tenancy/scope": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.tenancy.TenantContextBridge;",
            "import io.cratis.arc.tenancy.TenantId;",
        ),
        prelude="""
            static String doWork(TenantId current) { return ""; }
        """,
    ),
    "guides/ambient-tenancy/nested": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.tenancy.TenantContextBridge;",
            "import io.cratis.arc.tenancy.TenantId;",
        ),
        prelude="""
            static void use(TenantId current) { }
        """,
    ),
    "guides/spring-data/index/repository-in-query": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.FromServices;",
            "import io.cratis.arc.artifacts.ReadModel;",
            "import jakarta.persistence.Entity;",
            "import jakarta.persistence.Id;",
            "import java.util.List;",
            "import org.springframework.data.jpa.repository.JpaRepository;",
        ),
    ),
    "guides/queries/paths-and-services": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.FromServices;",
            "import io.cratis.arc.artifacts.ReadModel;",
            "import io.cratis.arc.authorization.AllowAnonymous;",
            "import io.cratis.arc.queries.Path;",
            "import java.util.List;",
        ),
        prelude="""
            interface TaskRepository {
                TaskView byId(String id);
                List<TaskView> all();
            }
        """,
    ),
    "guides/spring-data/concepts-mongodb/scalar-converters": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.concepts.ConceptAs;",
            "import org.springframework.core.convert.converter.Converter;",
            "import org.springframework.data.convert.ReadingConverter;",
            "import org.springframework.data.convert.WritingConverter;",
        ),
        prelude="""
            record TextValue(String value) implements ConceptAs<String> { }
        """,
    ),
    "guides/spring-data/concepts-mongodb/register-conversions": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.concepts.ConceptAs;",
            "import org.springframework.core.convert.converter.Converter;",
            "import org.springframework.data.convert.ReadingConverter;",
            "import org.springframework.data.convert.WritingConverter;",
            "import org.springframework.data.mongodb.core.convert.MongoCustomConversions;",
        ),
        prelude="""
            record TextValue(String value) implements ConceptAs<String> { }

            @WritingConverter
            class UuidWrite implements Converter<java.util.UUID, java.util.UUID> {
                @Override public java.util.UUID convert(java.util.UUID source) { return source; }
            }
            @ReadingConverter
            class UuidRead implements Converter<java.util.UUID, java.util.UUID> {
                @Override public java.util.UUID convert(java.util.UUID source) { return source; }
            }
            @WritingConverter
            class TextWrite implements Converter<TextValue, String> {
                @Override public String convert(TextValue source) { return source.value(); }
            }
            @ReadingConverter
            class TextRead implements Converter<String, TextValue> {
                @Override public TextValue convert(String source) { return new TextValue(source); }
            }
            @WritingConverter
            class LongWrite implements Converter<Long, Long> {
                @Override public Long convert(Long source) { return source; }
            }
            @ReadingConverter
            class LongRead implements Converter<Long, Long> {
                @Override public Long convert(Long source) { return source; }
            }
        """,
    ),
    "guides/spring-data/concepts-mongodb/programmatic-template": JavaSnippetContext(
        imports=(
            "import com.mongodb.client.MongoClient;",
            "import java.util.Set;",
            "import org.springframework.data.mongodb.core.MongoTemplate;",
            "import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;",
            "import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;",
            "import org.springframework.data.mongodb.core.convert.MappingMongoConverter;",
            "import org.springframework.data.mongodb.core.convert.MongoCustomConversions;",
            "import org.springframework.data.mongodb.core.mapping.MongoMappingContext;",
            "import org.springframework.data.mongodb.repository.MongoRepository;",
            "import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;",
        ),
        prelude="""
            class UuidRow { }
            class TextRow { }
            class LongRow { }
            interface TextRows extends MongoRepository<TextRow, String> { }
        """,
    ),
    "guides/spring-data/concepts-jpa/attribute-converter": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.concepts.ConceptAs;",
            "import jakarta.persistence.AttributeConverter;",
            "import jakarta.persistence.Converter;",
            "import java.util.Objects;",
        ),
    ),
    "guides/spring-data/concepts-jpa/embedded-id": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.ReadModel;",
            "import io.cratis.arc.concepts.ConceptAs;",
            "import jakarta.persistence.AttributeConverter;",
            "import jakarta.persistence.AttributeOverride;",
            "import jakarta.persistence.Column;",
            "import jakarta.persistence.Convert;",
            "import jakarta.persistence.Converter;",
            "import jakarta.persistence.Embeddable;",
            "import jakarta.persistence.EmbeddedId;",
            "import jakarta.persistence.Entity;",
            "import java.io.Serializable;",
            "import java.util.UUID;",
        ),
        prelude="""
            record TextValue(String value) implements ConceptAs<String> { }

            @Converter(autoApply = false)
            class TextConverter implements AttributeConverter<TextValue, String> {
                @Override
                public String convertToDatabaseColumn(TextValue attribute) {
                    return attribute == null ? null : attribute.value();
                }

                @Override
                public TextValue convertToEntityAttribute(String dbData) {
                    return dbData == null ? null : new TextValue(dbData);
                }
            }
        """,
    ),
    "guides/execution-scopes/contract": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.commands.CommandContext;",
            "import io.cratis.arc.results.CommandResult;",
            "import java.util.concurrent.CompletionStage;",
        ),
    ),
    "guides/execution-scopes/register": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.commands.CommandContext;",
            "import io.cratis.arc.java.BlockingCommandExecutionScope;",
            "import io.cratis.arc.results.CommandResult;",
            "import org.springframework.stereotype.Component;",
        ),
        prelude="""
            interface UnitOfWork {
                void begin();
                void commit();
                void rollback();
            }
        """,
    ),
    "reference/configuration/configure-object-mapper": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.json.ArcObjectMapper;",
            "import tools.jackson.databind.ObjectMapper;",
            "import tools.jackson.databind.json.JsonMapper;",
        ),
    ),
    "guides/command-keys/declared-key": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.Command;",
            "import io.cratis.arc.artifacts.CommandKey;",
            "import io.cratis.arc.artifacts.FromServices;",
            "import io.cratis.arc.concepts.ConceptAs;",
            "import java.util.UUID;",
        ),
        prelude="""
            record OrderId(UUID value) implements ConceptAs<UUID> {}
            record Address(String line, String postcode) {}
            interface OrderRepository {
                void updateAddress(OrderId orderId, Address address);
            }
        """,
    ),
    "guides/command-keys/computed-key": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.artifacts.Command;",
            "import io.cratis.arc.artifacts.FromServices;",
            "import io.cratis.arc.commands.CommandKeyProvider;",
        ),
        prelude="""
            interface ArchiveService {
                void run(String tenant, int year);
            }
        """,
    ),
    "guides/read-model-naming/naming-policy": JavaSnippetContext(
        prelude="",
    ),
    "guides/read-model-naming/default-policy": JavaSnippetContext(
        kind="member",
        imports=(
            "import io.cratis.arc.naming.NamingPolicy;",
            "import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy;",
        ),
    ),
    "guides/read-model-naming/override-policy": JavaSnippetContext(
        imports=(
            "import io.cratis.arc.naming.NamingPolicy;",
            "import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy;",
            "import org.springframework.context.annotation.Bean;",
            "import org.springframework.context.annotation.Configuration;",
        ),
        prelude="""
            class PersonView {
                String id;
            }
        """,
    ),
    "scenarios/provide-data-to-a-command/assess-loan": JavaSnippetContext(
        fixtures=("loan",),
        imports=(JAVA_IMPORT_COMMAND,),
    ),
    "scenarios/provide-data-to-a-command/cancellation": JavaSnippetContext(
        fixtures=("loan",),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_COMPLETION_STAGE, JAVA_IMPORT_COMPLETABLE_FUTURE),
    ),
    "scenarios/provide-data-to-a-command/provider-owned-state": JavaSnippetContext(
        kind="member",
        fixtures=("order",),
        imports=(JAVA_IMPORT_OPTIONAL,),
    ),
    "scenarios/provide-data-to-a-command/several-values": JavaSnippetContext(
        kind="member",
        fixtures=("loan",),
        imports=(JAVA_IMPORT_PROVIDED_VALUES,),
        host="""
            private LoanId loanId;
            private ApplicantId applicant;
        """,
    ),
    "scenarios/provide-data-to-a-command/short-circuit": JavaSnippetContext(
        kind="member",
        fixtures=("loan",),
        imports=(JAVA_IMPORT_VALIDATION_RESULT, JAVA_IMPORT_LIST),
        host="""
            private LoanId loanId;
            private ApplicantId applicant;
        """,
    ),
    "scenarios/provide-data-to-a-command/test-the-decision": JavaSnippetContext(
        kind="member",
        fixtures=("loan",),
        imports=(
            JAVA_IMPORT_COMMAND,
            JAVA_IMPORT_UUID,
            JAVA_IMPORT_TEST,
            JAVA_IMPORT_ASSERT_EQUALS,
        ),
        prelude="""
            @Command
            record AssessLoan(LoanId loanId, ApplicantId applicant) {
                LoanAssessment handle(CreditScore creditScore) {
                    return new LoanAssessment(loanId, creditScore);
                }
            }
        """,
    ),
    "scenarios/query-related-data/books-for-author": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(*JAVA_OBSERVABLE_QUERY_IMPORTS, JAVA_IMPORT_MONGO_OBSERVATIONS, JAVA_IMPORT_CRITERIA),
    ),
    "scenarios/return-a-result-or-error/handle-result": JavaSnippetContext(
        kind="member",
        fixtures=("concepts", "library"),
        imports=(
            JAVA_IMPORT_FROM_SERVICES,
            JAVA_IMPORT_VALIDATION_FAILURE,
            JAVA_IMPORT_VALIDATION_RESULT,
            JAVA_IMPORT_LIST,
        ),
        host="""
            private AuthorId id;
            private AuthorName name;
        """,
    ),
    "scenarios/test-a-command/command-under-test": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(JAVA_IMPORT_COMMAND, *JAVA_BLOCKING_VALIDATOR_IMPORTS),
    ),
    "scenarios/test-a-command/spec": JavaSnippetContext(
        # The command under test is a prelude rather than a second file, because Java's
        # one-public-type-per-file rule makes a whole-file snippet the wrong shape here.
        fixtures=("concepts", "generatedmodule"),
        imports=(
            JAVA_IMPORT_COMMAND,
            JAVA_IMPORT_GENERATED_MODULE,
            JAVA_IMPORT_ARRAY_LIST,
            JAVA_IMPORT_LIST,
            JAVA_IMPORT_UUID,
            JAVA_IMPORT_TEST,
            JAVA_IMPORT_ASSERT_EQUALS,
        ),
        prelude="""
            interface AuthorRegistration {
                void register(AuthorId id, AuthorName name);
            }

            @Command
            record RecordAuthor(AuthorId id, AuthorName name) {
                void handle(AuthorRegistration registration) {
                    registration.register(id, name);
                }
            }
        """,
    ),
    "scenarios/use-current-state-in-a-command/chronicle-commands": JavaSnippetContext(
        fixtures=("ledger",),
        hides=("Withdraw",),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_COMMAND_KEY, JAVA_IMPORT_BIG_DECIMAL),
    ),
    "scenarios/use-current-state-in-a-command/pin-read-model": JavaSnippetContext(
        kind="member",
        fixtures=("ledger", "generatedmodule"),
        imports=(
            JAVA_IMPORT_BEFORE_EACH,
            JAVA_IMPORT_BIG_DECIMAL,
            JAVA_IMPORT_COMMAND_SCENARIO,
            JAVA_IMPORT_GENERATED_MODULE,
        ),
        host=JAVA_SCENARIO_HOST,
    ),
    "scenarios/use-current-state-in-a-command/seed-events": JavaSnippetContext(
        kind="member",
        fixtures=("ledger", "generatedmodule"),
        imports=(
            JAVA_IMPORT_BEFORE_EACH,
            JAVA_IMPORT_BIG_DECIMAL,
            JAVA_IMPORT_COMMAND_SCENARIO,
            JAVA_IMPORT_CHRONICLE_SCENARIOS,
            JAVA_IMPORT_GENERATED_MODULE,
        ),
        host=JAVA_SCENARIO_HOST,
    ),
    "scenarios/use-current-state-in-a-command/register-customer-validator": JavaSnippetContext(
        imports=(
            JAVA_IMPORT_COMMAND,
            JAVA_IMPORT_COMMAND_KEY,
            JAVA_IMPORT_OPTIONAL,
            JAVA_IMPORT_UUID,
            *JAVA_BLOCKING_VALIDATOR_IMPORTS,
        ),
        prelude="""
            record Customer(UUID id, String name) {
            }

            interface CustomerRepository {
                Optional<Customer> findById(UUID id);
            }

            @Command
            record RegisterCustomer(@CommandKey UUID id, String name) {
            }
        """,
    ),
    "scenarios/use-current-state-in-a-command/rename-author-validator": JavaSnippetContext(
        fixtures=("concepts", "library"),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_COMMAND_KEY, *JAVA_BLOCKING_VALIDATOR_IMPORTS),
        prelude="""
            @Command
            record RenameAuthor(@CommandKey AuthorId id, AuthorName newName) {
            }
        """,
    ),
    "scenarios/use-current-state-in-a-command/rename-author": JavaSnippetContext(
        fixtures=("concepts", "library"),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES),
    ),
    "scenarios/use-current-state-in-a-command/required-order-state": JavaSnippetContext(
        fixtures=("order",),
        # The snippet's whole point is the command declaring its own required read-model
        # parameter, so it declares SubmitOrder rather than borrowing the fixture's.
        hides=("SubmitOrder",),
        imports=(
            JAVA_IMPORT_COMMAND,
            JAVA_IMPORT_COMMAND_KEY,
            JAVA_IMPORT_FROM_SERVICES,
            JAVA_IMPORT_UUID,
        ),
        prelude="""
            interface OrderSubmissions {
                void submit(UUID id);
            }
        """,
    ),
    "scenarios/validate-a-command/command-rule": JavaSnippetContext(
        fixtures=("concepts", "librarycommands"),
        imports=JAVA_BLOCKING_VALIDATOR_IMPORTS,
    ),
    "scenarios/validate-a-command/concept-rule": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(
            JAVA_IMPORT_COMPONENT,
            JAVA_IMPORT_CONCEPT_VALIDATOR,
            JAVA_IMPORT_VALIDATION_RESULT,
            JAVA_IMPORT_LIST,
        ),
    ),
    "scenarios/validate-a-command/service-rule": JavaSnippetContext(
        fixtures=("concepts", "librarycommands"),
        imports=JAVA_ASYNC_VALIDATOR_IMPORTS,
    ),
    "scenarios/validate-a-command/state-rule": JavaSnippetContext(
        fixtures=("order",),
        imports=JAVA_BLOCKING_VALIDATOR_IMPORTS,
    ),
    "frontend/index/open-account": JavaSnippetContext(
        fixtures=("account",),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES),
    ),
    "frontend/react/proxy-generation/open-debit-account": JavaSnippetContext(
        # Deliberately self-contained: the page teaches what a whole backend file looks like
        # before the generator turns it into TypeScript.
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES),
    ),
    "frontend/react/commands/index/command-payload": JavaSnippetContext(
        fixtures=("account",),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES),
    ),
    "frontend/react/queries/usage/parameterized-query": JavaSnippetContext(
        fixtures=("account",),
        imports=(JAVA_IMPORT_READ_MODEL, JAVA_IMPORT_FROM_SERVICES, JAVA_IMPORT_LIST),
        prelude="""
            interface DebitAccountRepository {
                List<DebitAccount> findByNameStartingWith(String prefix);
            }
        """,
    ),
    "frontend/react/command-form/validation/profile-command": JavaSnippetContext(),
    "frontend/react/command-form/auto-server-validation/server-only-rule": JavaSnippetContext(
        # A replacement for the validator the validation page declares, so it stays a fragment
        # rather than a second declaration of UpdateProfile in the same wrapper.
        imports=(JAVA_IMPORT_COMMAND, *JAVA_ASYNC_VALIDATOR_IMPORTS),
        prelude="""
            @Command
            record UpdateProfile(String name, String email) {
            }
        """,
    ),
    "tutorial/first-slice/author-slice": JavaSnippetContext(
        # The chapter's own Author is the read model the reader declares, so the snippet
        # declares it and the prelude supplies only the repository it saves through.
        fixtures=("concepts",),
        imports=(JAVA_IMPORT_COMMAND, *JAVA_OBSERVABLE_QUERY_IMPORTS),
        prelude="""
            interface AuthorRepository {
                void save(Author author);
            }
        """,
    ),
    "tutorial/validation/author-name-rule": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(
            JAVA_IMPORT_COMPONENT,
            JAVA_IMPORT_CONCEPT_VALIDATOR,
            JAVA_IMPORT_VALIDATION_RESULT,
            JAVA_IMPORT_LIST,
        ),
    ),
    "tutorial/validation/duplicate-name-rule": JavaSnippetContext(
        fixtures=("concepts", "library", "librarycommands"),
        imports=JAVA_BLOCKING_VALIDATOR_IMPORTS,
    ),
    "tutorial/books-and-relationships/book-concepts": JavaSnippetContext(
        # Deliberately fixture-free: the chapter is teaching the reader to declare these
        # two concepts, so importing the fixture's BookId would collide with the snippet.
        imports=(JAVA_IMPORT_CONCEPT_AS, JAVA_IMPORT_UUID),
    ),
    "tutorial/books-and-relationships/add-book": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES),
        prelude="""
            record Book(BookId id, AuthorId authorId, BookTitle title) {
            }

            interface BookRepository {
                void save(Book book);
            }
        """,
    ),
    "tutorial/books-and-relationships/books-for-author": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(*JAVA_OBSERVABLE_QUERY_IMPORTS, JAVA_IMPORT_MONGO_OBSERVATIONS, JAVA_IMPORT_CRITERIA),
    ),
    "tutorial/authorization/roles-on-command": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES, JAVA_IMPORT_ROLES),
        prelude="""
            record Author(AuthorId id, AuthorName name) {
            }

            interface AuthorRepository {
                void save(Author author);
            }
        """,
    ),
    "tutorial/authorization/roles-on-query": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(*JAVA_OBSERVABLE_QUERY_IMPORTS, JAVA_IMPORT_ROLES),
    ),
    "tutorial/real-time/observable-query": JavaSnippetContext(
        kind="member",
        fixtures=("concepts", "library"),
        imports=(
            JAVA_IMPORT_FROM_SERVICES,
            JAVA_IMPORT_MONGO_QUERY,
            JAVA_IMPORT_FLOW,
            JAVA_IMPORT_LIST,
        ),
    ),
    "tutorial/real-time/one-shot-query": JavaSnippetContext(
        kind="member",
        fixtures=("concepts", "library"),
        imports=(JAVA_IMPORT_FROM_SERVICES, JAVA_IMPORT_LIST),
    ),
    "understanding-identity-and-access/authorization": JavaSnippetContext(
        fixtures=("concepts",),
        imports=(
            JAVA_IMPORT_COMMAND,
            JAVA_IMPORT_READ_MODEL,
            JAVA_IMPORT_FROM_SERVICES,
            JAVA_IMPORT_FLOW,
            JAVA_IMPORT_LIST,
            JAVA_IMPORT_ROLES,
            JAVA_IMPORT_ALLOW_ANONYMOUS,
        ),
        # This snippet declares its own Author, so it cannot use the shared library
        # fixture's repository - that one stores the shared Author.
        prelude="""
            interface AuthorRepository {
                void save(Author author);

                Flow.Publisher<List<Author>> observeAll();
            }
        """,
    ),
    "understanding-identity-and-access/identity-provider": JavaSnippetContext(
        kind="member",
        fixtures=("membership",),
        imports=(
            JAVA_IMPORT_ASYNC_IDENTITY_PROVIDER,
            JAVA_IMPORT_ASYNC_IDENTITY_ADAPTER,
            JAVA_IMPORT_IDENTITY_DETAILS,
            JAVA_IMPORT_IDENTITY_DETAILS_PROVIDER,
            JAVA_IMPORT_IDENTITY_CONTEXT,
            JAVA_IMPORT_BEAN,
            JAVA_IMPORT_COMPLETION_STAGE,
            JAVA_IMPORT_COMPLETABLE_FUTURE,
        ),
    ),
    "understanding-the-proxy-boundary/register-author": JavaSnippetContext(
        fixtures=("concepts", "library"),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES),
    ),
    "understanding-the-proxy-boundary/rename-property": JavaSnippetContext(
        fixtures=("concepts", "library"),
        imports=(JAVA_IMPORT_COMMAND, JAVA_IMPORT_FROM_SERVICES),
    ),
}


@dataclass
class Snippet:
    """One snippet file resolved into something compilable."""

    identifier: str
    path: Path
    language: str = SNIPPET_LANGUAGE
    code: str = ""
    unsupported: bool = False


def display_path(path: Path) -> str:
    # Never let path formatting throw on the error path: a failure message that crashes is a
    # failure nobody can read.
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def snippet_identifier(path: Path, root: Path) -> str:
    return path.relative_to(root).with_suffix("").as_posix()


def sanitized(identifier: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", identifier)


def snippet_files(root: Path) -> list[Path]:
    if not root.is_dir():
        raise SnippetError(f"Snippet root does not exist: {display_path(root)}")

    files = sorted([*root.rglob("*.md"), *root.rglob("*.mdx")])
    seen: dict[str, Path] = {}
    for path in files:
        identifier = snippet_identifier(path, root)
        if identifier in seen:
            raise SnippetError(
                f"Duplicate snippet id {identifier!r}: "
                f"{display_path(seen[identifier])} and {display_path(path)}")
        seen[identifier] = path
    return files


def read_snippet(path: Path, root: Path, language_name: str) -> Snippet:
    """Enforce the snippet contract and return the fenced code."""
    identifier = snippet_identifier(path, root)
    matches = FENCE_RE.findall(path.read_text(encoding="utf-8"))

    if len(matches) != 1:
        raise SnippetError(
            f"{display_path(path)} must contain exactly one fenced code block, found {len(matches)}")

    language, code = matches[0]

    if language == UNSUPPORTED_FENCE_LANGUAGE:
        if UNSUPPORTED_MARKER not in code:
            raise SnippetError(
                f"{display_path(path)} uses a {UNSUPPORTED_FENCE_LANGUAGE!r} fence but does not contain "
                f"the unsupported marker {UNSUPPORTED_MARKER!r}. A non-{language_name} fence is only "
                f"allowed to state that this client does not support the workflow.")
        return Snippet(identifier=identifier, path=path, language=language_name, unsupported=True)

    if language != language_name:
        raise SnippetError(
            f"{display_path(path)} must use a {language_name!r} code fence, got {language!r}")

    code = code.strip()
    if not code:
        raise SnippetError(f"{display_path(path)} contains no code to compile")

    # Kept verbatim here. Import hoisting happens per snippet kind at generation time,
    # because a whole-file snippet must keep its own imports where it wrote them.
    return Snippet(identifier=identifier, path=path, language=language_name, code=code)


def split_imports(code: str) -> tuple[list[str], str]:
    """Hoist top-level import directives out of a fragment so it can be wrapped.

    A whole-file snippet keeps its own imports; this only matters for fragments, which
    would otherwise have to be rewritten to compile.
    """
    imports: list[str] = []
    body: list[str] = []
    for line in code.splitlines():
        if IMPORT_DIRECTIVE_RE.match(line.strip()) and line == line.lstrip():
            imports.append(line)
        else:
            body.append(line)
    return imports, "\n".join(body).strip()


def fixture_package(name: str) -> str:
    fixture = FIXTURES[name]
    return fixture.package_name or f"{FIXTURE_PACKAGE_ROOT}.{name}"


def fixture_imports(context: SnippetContext, identifier: str) -> list[str]:
    """Explicit imports for every fixture type the snippet is allowed to see."""
    imports: list[str] = []
    imported_names: dict[str, str] = {}
    for name in context.fixtures:
        if name not in FIXTURES:
            raise SnippetError(f"Snippet {identifier!r} asks for unknown domain fixture {name!r}")
        for type_name in FIXTURES[name].types:
            if type_name in context.hides:
                continue
            if type_name in imported_names:
                raise SnippetError(
                    f"Snippet {identifier!r} imports {type_name!r} from both "
                    f"{imported_names[type_name]!r} and {name!r}; fixture types must not collide")
            imported_names[type_name] = name
            imports.append(f"import {fixture_package(name)}.{type_name}")

    unknown_hidden = sorted(set(context.hides) - {
        type_name for name in context.fixtures for type_name in FIXTURES[name].types})
    if unknown_hidden:
        raise SnippetError(
            f"Snippet {identifier!r} hides fixture type(s) no listed fixture declares: "
            f"{', '.join(unknown_hidden)}")

    return imports


GENERATED_HEADER = "// Generated from {source} by Documentation/validate-client-snippets.py. Do not edit."
# Fragments are written for a reader, not for a compiler: a `@Bean` factory nobody calls
# and a command property only the snippet reads are normal. This build compiles Kotlin with
# allWarningsAsErrors, so those would otherwise fail as warnings rather than as real defects.
# NAME_SHADOWING is here for the same reason: a documented spec deliberately names an
# anonymous implementation's parameters after the values under test.
SUPPRESSIONS = (
    '@file:Suppress("unused", "UNUSED_PARAMETER", "UNUSED_VARIABLE", "UNUSED_EXPRESSION", '
    '"RedundantSuspendModifier", "NAME_SHADOWING")')


def generate_snippet_source(snippet: Snippet, corrupt: bool = False) -> str:
    """Render one snippet into its own compilation unit.

    Each fragment snippet gets its own package, so two snippets declaring the same
    supporting type cannot collide and a compiler error names the snippet. A "file"
    snippet declares its own package - it is a whole file, not a fragment - and is emitted
    verbatim.
    """
    context = SNIPPET_CONTEXTS.get(snippet.identifier)
    if context is None:
        raise SnippetError(
            f"Snippet {snippet.identifier!r} has no entry in SNIPPET_CONTEXTS. Add one saying which "
            "shape it is and which fixtures supply the types it references.")
    if context.kind not in KNOWN_KINDS:
        raise SnippetError(f"Snippet {snippet.identifier!r} declares unknown context kind {context.kind!r}")
    if context.host and context.kind != "member":
        raise SnippetError(
            f"Snippet {snippet.identifier!r} declares a host on a {context.kind!r} context; "
            "only a 'member' context has a generated enclosing class")

    header = GENERATED_HEADER.format(source=display_path(snippet.path))
    planted = (
        "\n\n// --self-test planted defect: a reference to a type that exists nowhere in Arc.\n"
        "internal val plantedSelfTestDefect: ThisTypeDoesNotExistAnywhereInArcKotlin? = null"
        if corrupt else "")

    if context.kind == "file":
        if context.imports or context.prelude or context.hides:
            raise SnippetError(
                f"Snippet {snippet.identifier!r} is a whole file; it cannot take extra imports, "
                "hides or a prelude")
        # A file annotation has to precede the package directive, so it goes above the
        # snippet rather than inside it; the snippet itself is still emitted verbatim.
        return "\n".join([header, SUPPRESSIONS, "", snippet.code + planted, ""])

    snippet_imports, snippet_body = split_imports(snippet.code)
    prelude = textwrap.dedent(context.prelude).strip()
    combined = "\n\n".join(part for part in (prelude, snippet_body) if part)
    imports = sorted({*context.imports, *fixture_imports(context, snippet.identifier), *snippet_imports})

    if context.kind == "declaration":
        body = combined
    elif context.kind == "member":
        # The generated enclosing class stands in for the `@Command` record, `@Configuration`
        # class or test class the member really lives on, so the fragment can read the
        # enclosing type's properties exactly as the rendered snippet does.
        declaration = f"internal class SnippetHost({context.host}) {{" if context.host else "internal class SnippetHost {"
        body = "\n".join([declaration, textwrap.indent(combined, "    "), "}"])
    else:
        # A `@JvmStatic` query method only compiles inside a companion object - which is
        # exactly where a real `@ReadModel` query method lives.
        body = "\n".join([
            "internal class SnippetHost {",
            "    companion object {",
            textwrap.indent(combined, "        "),
            "    }",
            "}",
        ])

    return "\n".join([
        header,
        SUPPRESSIONS,
        "",
        f"package {SNIPPET_PACKAGE_ROOT}.{sanitized(snippet.identifier)}",
        "",
        *imports,
        "",
        body + planted,
        "",
    ])


def generate_fixture_source(name: str) -> str:
    fixture = FIXTURES[name]
    return "\n".join([
        f"// Shared {name!r} snippet fixture generated by Documentation/validate-client-snippets.py. Do not edit.",
        SUPPRESSIONS,
        "",
        f"package {fixture_package(name)}",
        "",
        *sorted(fixture.imports),
        "",
        textwrap.dedent(fixture.declarations).strip(),
        "",
    ])


def write_generated(snippets: list[Snippet], corrupt: str | None = None) -> list[Path]:
    GENERATED_DIR.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []

    required = sorted({
        name
        for snippet in snippets
        for name in SNIPPET_CONTEXTS.get(snippet.identifier, SnippetContext()).fixtures})
    for name in required:
        path = GENERATED_DIR / f"Fixture_{sanitized(name)}.kt"
        path.write_text(generate_fixture_source(name), encoding="utf-8")
        written.append(path)

    for snippet in snippets:
        source = generate_snippet_source(snippet, corrupt=snippet.identifier == corrupt)
        path = GENERATED_DIR / f"Snippet_{sanitized(snippet.identifier)}.kt"
        path.write_text(source, encoding="utf-8")
        written.append(path)

    return written


# Java fragments are written for a reader, not for a compiler: a `@Bean` factory nobody
# calls and a record component only the snippet reads are normal. This build compiles Java
# with -Xlint:all -Werror, so an exception without a serialVersionUID or an unused
# declaration would otherwise fail as a warning rather than as a real defect.
JAVA_SUPPRESSIONS = '@SuppressWarnings({"unused", "serial", "rawtypes", "unchecked", "deprecation", "this-escape"})'


def split_java_imports(code: str) -> tuple[list[str], str]:
    """Hoist top-level import declarations out of a fragment so it can be wrapped.

    A Java import declaration must precede every type declaration in the file, so a snippet
    that shows its own imports cannot keep them where it wrote them once the snippet becomes
    a member of a generated wrapper.
    """
    imports: list[str] = []
    body: list[str] = []
    for line in code.splitlines():
        if JAVA_IMPORT_DIRECTIVE_RE.match(line.strip()) and line == line.lstrip():
            imports.append(line)
        else:
            body.append(line)
    return imports, "\n".join(body).strip()


def java_fixture_imports(context: JavaSnippetContext, identifier: str) -> list[str]:
    """Explicit imports for every fixture type the snippet is allowed to see."""
    imports: list[str] = []
    imported_names: dict[str, str] = {}
    for name in context.fixtures:
        if name not in JAVA_FIXTURES:
            raise SnippetError(f"Java snippet {identifier!r} asks for unknown domain fixture {name!r}")
        for type_name in JAVA_FIXTURES[name].types:
            if type_name in context.hides:
                continue
            if type_name in imported_names:
                raise SnippetError(
                    f"Java snippet {identifier!r} imports {type_name!r} from both "
                    f"{imported_names[type_name]!r} and {name!r}; fixture types must not collide")
            imported_names[type_name] = name
            imports.append(f"import {JAVA_FIXTURE_PACKAGE}.Fixture_{sanitized(name)}.{type_name};")

    unknown_hidden = sorted(set(context.hides) - {
        type_name for name in context.fixtures for type_name in JAVA_FIXTURES[name].types})
    if unknown_hidden:
        raise SnippetError(
            f"Java snippet {identifier!r} hides fixture type(s) no listed fixture declares: "
            f"{', '.join(unknown_hidden)}")

    return imports


def generate_java_snippet_source(snippet: Snippet, corrupt: bool = False) -> str:
    """Render one Java snippet into its own compilation unit.

    Every snippet becomes members of a wrapper interface named after it. Interface members
    are implicitly public and static, so a `public record` or a `final class` reads and
    compiles exactly as the reader's own top-level declaration would, and the wrapper name
    keeps two snippets that both declare `RegisterAuthor` apart without renaming anything.
    """
    context = JAVA_SNIPPET_CONTEXTS.get(snippet.identifier)
    if context is None:
        raise SnippetError(
            f"Java snippet {snippet.identifier!r} has no entry in JAVA_SNIPPET_CONTEXTS. Add one saying "
            "which shape it is and which fixtures supply the types it references.")
    if context.kind not in JAVA_KNOWN_KINDS:
        raise SnippetError(
            f"Java snippet {snippet.identifier!r} declares unknown context kind {context.kind!r}")
    if context.host and context.kind != "member":
        raise SnippetError(
            f"Java snippet {snippet.identifier!r} declares a host on a {context.kind!r} context; "
            "only a 'member' context has a generated enclosing class")

    snippet_imports, snippet_body = split_java_imports(snippet.code)
    prelude = textwrap.dedent(context.prelude).strip()
    imports = sorted({*context.imports, *java_fixture_imports(context, snippet.identifier), *snippet_imports})

    if context.kind == "declaration":
        members = "\n\n".join(part for part in (prelude, snippet_body) if part)
        body = textwrap.indent(members, "    ")
    else:
        # The generated enclosing class stands in for the `@Command` record, `@Configuration`
        # class or test class the member really lives on, so the fragment can read the
        # enclosing type's fields exactly as the rendered snippet does.
        host = textwrap.dedent(context.host).strip()
        members = "\n\n".join(part for part in (host, prelude, snippet_body) if part)
        body = "\n".join([
            "    final class SnippetHost {",
            textwrap.indent(members, "        "),
            "    }",
        ])

    planted = (
        "\n\n    // --self-test planted defect: a reference to a type that exists nowhere in Arc.\n"
        "    ThisTypeDoesNotExistAnywhereInArcJava plantedSelfTestDefect = null;"
        if corrupt else "")

    return "\n".join([
        GENERATED_HEADER.format(source=display_path(snippet.path)),
        f"package {JAVA_SNIPPET_PACKAGE};",
        "",
        *imports,
        "",
        JAVA_SUPPRESSIONS,
        f"interface Snippet_{sanitized(snippet.identifier)} {{",
        body + planted,
        "}",
        "",
    ])


def generate_java_fixture_source(name: str) -> str:
    fixture = JAVA_FIXTURES[name]
    return "\n".join([
        f"// Shared {name!r} Java snippet fixture generated by "
        "Documentation/validate-client-snippets.py. Do not edit.",
        f"package {JAVA_FIXTURE_PACKAGE};",
        "",
        *sorted(fixture.imports),
        "",
        JAVA_SUPPRESSIONS,
        f"public interface Fixture_{sanitized(name)} {{",
        textwrap.indent(textwrap.dedent(fixture.declarations).strip(), "    "),
        "}",
        "",
    ])


def write_java_generated(snippets: list[Snippet], corrupt: str | None = None) -> list[Path]:
    JAVA_SNIPPET_DIR.mkdir(parents=True, exist_ok=True)
    JAVA_FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []

    required = sorted({
        name
        for snippet in snippets
        for name in JAVA_SNIPPET_CONTEXTS.get(snippet.identifier, JavaSnippetContext()).fixtures})
    for name in required:
        path = JAVA_FIXTURE_DIR / f"Fixture_{sanitized(name)}.java"
        path.write_text(generate_java_fixture_source(name), encoding="utf-8")
        written.append(path)

    for snippet in snippets:
        source = generate_java_snippet_source(snippet, corrupt=snippet.identifier == corrupt)
        path = JAVA_SNIPPET_DIR / f"Snippet_{sanitized(snippet.identifier)}.java"
        path.write_text(source, encoding="utf-8")
        written.append(path)

    return written


def clean_generated(written: list[Path]) -> None:
    for path in written:
        path.unlink(missing_ok=True)
    # rmdir removes only an empty directory, so a directory still holding something a human
    # put there survives. Two language trees means the walk cannot stop at the first
    # non-empty directory the way a single chain could.
    for directory in GENERATED_PRUNE_DIRS:
        try:
            directory.rmdir()
        except OSError:
            continue


def compile_generated(tasks: tuple[str, ...]) -> tuple[int, str]:
    gradlew = REPO_ROOT / "gradlew"
    if not gradlew.is_file():
        raise ToolchainMissing(f"No Gradle wrapper at {display_path(gradlew)}")

    command = [str(gradlew), *tasks, "--no-configuration-cache"]
    print(f"Running {' '.join(command)}", flush=True)
    try:
        completed = subprocess.run(
            command,
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as error:
        raise ToolchainMissing(f"Could not start Gradle: {error}") from error

    output = completed.stdout + completed.stderr
    # Print the real output verbatim: a blocked run must be recognizable as blocked, and a
    # compile failure must name the snippet that failed.
    print(output, end="" if output.endswith("\n") else "\n", flush=True)

    if completed.returncode != 0:
        for marker in TOOLCHAIN_MISSING_MARKERS:
            if marker in output:
                raise ToolchainMissing(
                    f"Gradle could not start a JVM toolchain - it reported {marker!r}")

    return completed.returncode, output


def collect(root: Path, language_name: str, contexts: dict) -> tuple[list[Snippet], list[Snippet]]:
    files = snippet_files(root)

    # Non-vacuity fuse. A checker that validates nothing and prints a tick is worse than no
    # checker: it turns "nobody looked" into a green check.
    if not files:
        raise SnippetError(
            f"No {language_name} client snippets found under {display_path(root)}. Either the snippets "
            "moved or this validator is looking in the wrong place; validating zero snippets is a "
            "failure, not a pass.")

    snippets = [read_snippet(path, root, language_name) for path in files]

    # A context for a snippet that no longer exists is a rule quietly deleted: the snippet
    # it used to describe may have been renamed and now has no context at all.
    known = {snippet.identifier for snippet in snippets}
    stale = sorted(set(contexts) - known)
    if stale:
        raise SnippetError(
            f"The {language_name} snippet context table has entries for snippets that do not exist: "
            f"{', '.join(stale)}")

    return (
        [snippet for snippet in snippets if not snippet.unsupported],
        [snippet for snippet in snippets if snippet.unsupported],
    )


def check_identifier_parity(kotlin: list[Snippet], java: list[Snippet]) -> None:
    """Both languages must answer the same snippet ids.

    The shared Arc pages expect every backend to answer every snippet, so a missing sibling
    is an oversight worth failing on rather than a language tab that quietly disappears from
    the published page.
    """
    kotlin_ids = {snippet.identifier for snippet in kotlin}
    java_ids = {snippet.identifier for snippet in java}

    missing_java = sorted(kotlin_ids - java_ids)
    missing_kotlin = sorted(java_ids - kotlin_ids)
    if missing_java or missing_kotlin:
        problems = []
        if missing_java:
            problems.append(
                f"no Java snippet under {display_path(JAVA_SNIPPET_ROOT)} for: {', '.join(missing_java)}")
        if missing_kotlin:
            problems.append(
                f"no Kotlin snippet under {display_path(SNIPPET_ROOT)} for: {', '.join(missing_kotlin)}")
        raise SnippetError(
            "Kotlin and Java client snippets must answer the same ids - " + "; ".join(problems))


def report(language_name: str, compilable: list[Snippet], unsupported: list[Snippet], contexts: dict) -> None:
    print(
        f"Compiled {len(compilable)} {language_name} Arc client snippet(s) against Arc.Kotlin source "
        f"successfully"
        f"{f', skipped {len(unsupported)} unsupported marker(s)' if unsupported else ''}.")
    for snippet in compilable:
        print(f"  - {snippet.identifier} ({contexts[snippet.identifier].kind})")
    for snippet in unsupported:
        print(f"  - {snippet.identifier} (unsupported marker, not compiled)")


def print_generated(print_identifier: str) -> None:
    candidates = (
        GENERATED_DIR / f"Snippet_{sanitized(print_identifier)}.kt",
        GENERATED_DIR / f"Fixture_{sanitized(print_identifier)}.kt",
        JAVA_SNIPPET_DIR / f"Snippet_{sanitized(print_identifier)}.java",
        JAVA_FIXTURE_DIR / f"Fixture_{sanitized(print_identifier)}.java",
    )
    printed = False
    for target in candidates:
        if not target.is_file():
            continue
        printed = True
        print(f"----- {target.name} -----")
        print(target.read_text(encoding="utf-8"), end="")
        print(f"----- end {target.name} -----")
    if not printed:
        raise SnippetError(f"Nothing generated for {print_identifier!r}")


def run_self_test(kotlin: list[Snippet], java: list[Snippet]) -> int:
    """Plant a defect in each language and fail unless that language's compile rejects it.

    One planted defect is not enough here: the Java task runs after the Kotlin task, so a
    Kotlin failure would hide a Java detector that had stopped detecting anything. Each
    language therefore gets its own run with the other language left clean.
    """
    if not kotlin or not java:
        raise SnippetError("--self-test needs at least one compilable snippet in each language")

    for language_name, corrupt_kotlin, corrupt_java, tasks in (
        (SNIPPET_LANGUAGE, kotlin[0].identifier, None, (GRADLE_TASK,)),
        (JAVA_SNIPPET_LANGUAGE, None, java[0].identifier, (JAVA_GRADLE_TASK,)),
    ):
        planted = corrupt_kotlin or corrupt_java
        print(f"Self-test ({language_name}): planting a reference to a non-existent type in {planted!r}.")

        written: list[Path] = []
        try:
            written = [
                *write_generated(kotlin, corrupt=corrupt_kotlin),
                *write_java_generated(java, corrupt=corrupt_java),
            ]
            exit_code, _ = compile_generated(tasks)
        finally:
            clean_generated(written)

        if exit_code == 0:
            print(
                f"Self-test FAILED: the {language_name} compilation succeeded with a planted reference to "
                "a non-existent type, so this validator is not detecting anything.",
                file=sys.stderr)
            return 1
        print(
            f"Self-test passed ({language_name}): the planted defect failed compilation "
            f"(exit code {exit_code}).")

    return 0


def run(self_test: bool, dry_run: bool, print_identifier: str | None) -> int:
    kotlin_compilable, kotlin_unsupported = collect(SNIPPET_ROOT, SNIPPET_LANGUAGE, SNIPPET_CONTEXTS)
    java_compilable, java_unsupported = collect(
        JAVA_SNIPPET_ROOT, JAVA_SNIPPET_LANGUAGE, JAVA_SNIPPET_CONTEXTS)
    check_identifier_parity(
        kotlin_compilable + kotlin_unsupported, java_compilable + java_unsupported)

    print(
        f"Found {len(kotlin_compilable) + len(kotlin_unsupported)} client snippet(s) under "
        f"{display_path(SNIPPET_ROOT)} and "
        f"{len(java_compilable) + len(java_unsupported)} under {display_path(JAVA_SNIPPET_ROOT)}.")

    if self_test:
        return run_self_test(kotlin_compilable, java_compilable)

    if not kotlin_compilable and not java_compilable:
        print(
            f"All {len(kotlin_unsupported) + len(java_unsupported)} snippet(s) are unsupported markers "
            "- nothing to compile.")
        return 0

    written: list[Path] = []
    try:
        written = [
            *write_generated(kotlin_compilable),
            *write_java_generated(java_compilable),
        ]

        if print_identifier is not None:
            print_generated(print_identifier)

        if dry_run:
            print(
                "\nDRY RUN: generated "
                f"{len(written)} source file(s) and compiled NOTHING. This is not a verification pass.")
            for path in written:
                print(f"  - {display_path(path)}")
            return 0

        exit_code, _ = compile_generated((GRADLE_TASK, JAVA_GRADLE_TASK))
    finally:
        clean_generated(written)

    if exit_code != 0:
        print(f"Client snippet compilation failed with exit code {exit_code}.", file=sys.stderr)
        return 1

    report("Kotlin", kotlin_compilable, kotlin_unsupported, SNIPPET_CONTEXTS)
    report("Java", java_compilable, java_unsupported, JAVA_SNIPPET_CONTEXTS)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Plant a reference to a non-existent type in a snippet and fail unless compilation rejects it.")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Generate the sources and check the snippet contract without compiling. Not a verification pass.")
    parser.add_argument(
        "--print",
        dest="print_identifier",
        metavar="SNIPPET_ID",
        help="Print the generated Kotlin and Java source for one snippet id or fixture name.")
    arguments = parser.parse_args()

    try:
        return run(arguments.self_test, arguments.dry_run, arguments.print_identifier)
    except SnippetError as error:
        print(f"Client snippet validation failed: {error}", file=sys.stderr)
        return 1
    except ToolchainMissing as error:
        # Exit 2, distinct from a compile failure: this run found nothing, because it could
        # not look. Could-not-run is an unknown, and an unknown is never a pass.
        print(
            f"BLOCKED: no JVM toolchain - Kotlin and Java client snippets were NOT compiled. {error}",
            file=sys.stderr)
        print(
            "Install a JDK 17 toolchain (CI has one) and re-run "
            "python3 Documentation/validate-client-snippets.py.",
            file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
