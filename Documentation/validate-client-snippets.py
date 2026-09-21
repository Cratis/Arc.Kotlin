#!/usr/bin/env python3
# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

"""Compile every shared-docs Kotlin snippet against Arc.Kotlin's real modules.

`Documentation/client-snippets/**` is not a documentation page. It is the Kotlin side
of the language tabs the shared Arc pages render, pulled by the documentation site
from this repository and from each other Arc client repository. Nothing in a Markdown
file is compiled by anything, so without this gate a snippet is a string nobody checks:
a renamed annotation, a changed signature or an outright invented API keeps rendering
happily on the published site. `validate-doc-snippets.py` is a *source-contract* check
over an allow-list of symbol names - a name it does not list is invisible to it, so a
misspelled type passes. This gate is the one that actually compiles.

A snippet is a fragment, not a file, so each one declares a context in
`SNIPPET_CONTEXTS` saying which shape it is (`declaration`, `member`, `companion` or
`file`), which shared domain fixtures supply the types it references, which extra
imports it needs, and any prelude no fixture can supply. The snippet body itself is
emitted verbatim - never rewritten - so what compiles is exactly what a reader sees.

Generated sources go into the `:ContractTests` `documentationSnippet` source set; see
GRADLE_MODULE_RATIONALE below.

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
GENERATED_PRUNE_DIRS = (
    GENERATED_DIR,
    GENERATED_DIR.parent,
)

FENCE_RE = re.compile(r"```([^\s`]+)[^\n]*\n(.*?)\n```", re.DOTALL)
IMPORT_DIRECTIVE_RE = re.compile(r"^import\s+[A-Za-z_][A-Za-z0-9_.`]*(?:\s+as\s+[A-Za-z_][A-Za-z0-9_]*)?$")

SNIPPET_LANGUAGE = "kotlin"

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


@dataclass
class Snippet:
    """One snippet file resolved into something compilable."""

    identifier: str
    path: Path
    code: str = ""
    unsupported: bool = False


def display_path(path: Path) -> str:
    # Never let path formatting throw on the error path: a failure message that crashes is a
    # failure nobody can read.
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def snippet_identifier(path: Path) -> str:
    return path.relative_to(SNIPPET_ROOT).with_suffix("").as_posix()


def sanitized(identifier: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", "_", identifier)


def snippet_files() -> list[Path]:
    if not SNIPPET_ROOT.is_dir():
        raise SnippetError(f"Snippet root does not exist: {display_path(SNIPPET_ROOT)}")

    files = sorted([*SNIPPET_ROOT.rglob("*.md"), *SNIPPET_ROOT.rglob("*.mdx")])
    seen: dict[str, Path] = {}
    for path in files:
        identifier = snippet_identifier(path)
        if identifier in seen:
            raise SnippetError(
                f"Duplicate snippet id {identifier!r}: "
                f"{display_path(seen[identifier])} and {display_path(path)}")
        seen[identifier] = path
    return files


def read_snippet(path: Path) -> Snippet:
    """Enforce the snippet contract and return the fenced code."""
    identifier = snippet_identifier(path)
    matches = FENCE_RE.findall(path.read_text(encoding="utf-8"))

    if len(matches) != 1:
        raise SnippetError(
            f"{display_path(path)} must contain exactly one fenced code block, found {len(matches)}")

    language, code = matches[0]

    if language == UNSUPPORTED_FENCE_LANGUAGE:
        if UNSUPPORTED_MARKER not in code:
            raise SnippetError(
                f"{display_path(path)} uses a {UNSUPPORTED_FENCE_LANGUAGE!r} fence but does not contain "
                f"the unsupported marker {UNSUPPORTED_MARKER!r}. A non-{SNIPPET_LANGUAGE} fence is only "
                f"allowed to state that this client does not support the workflow.")
        return Snippet(identifier=identifier, path=path, unsupported=True)

    if language != SNIPPET_LANGUAGE:
        raise SnippetError(
            f"{display_path(path)} must use a {SNIPPET_LANGUAGE!r} code fence, got {language!r}")

    code = code.strip()
    if not code:
        raise SnippetError(f"{display_path(path)} contains no code to compile")

    # Kept verbatim here. Import hoisting happens per snippet kind at generation time,
    # because a whole-file snippet must keep its own imports where it wrote them.
    return Snippet(identifier=identifier, path=path, code=code)


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


def clean_generated(written: list[Path]) -> None:
    for path in written:
        path.unlink(missing_ok=True)
    for directory in GENERATED_PRUNE_DIRS:
        try:
            directory.rmdir()
        except OSError:
            break


def compile_generated() -> tuple[int, str]:
    gradlew = REPO_ROOT / "gradlew"
    if not gradlew.is_file():
        raise ToolchainMissing(f"No Gradle wrapper at {display_path(gradlew)}")

    command = [str(gradlew), GRADLE_TASK, "--no-configuration-cache"]
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


def collect() -> tuple[list[Snippet], list[Snippet]]:
    files = snippet_files()

    # Non-vacuity fuse. A checker that validates nothing and prints a tick is worse than no
    # checker: it turns "nobody looked" into a green check.
    if not files:
        raise SnippetError(
            f"No client snippets found under {display_path(SNIPPET_ROOT)}. Either the snippets moved "
            "or this validator is looking in the wrong place; validating zero snippets is a failure, "
            "not a pass.")

    snippets = [read_snippet(path) for path in files]

    # A context for a snippet that no longer exists is a rule quietly deleted: the snippet
    # it used to describe may have been renamed and now has no context at all.
    known = {snippet.identifier for snippet in snippets}
    stale = sorted(set(SNIPPET_CONTEXTS) - known)
    if stale:
        raise SnippetError(
            f"SNIPPET_CONTEXTS has entries for snippets that do not exist: {', '.join(stale)}")

    return (
        [snippet for snippet in snippets if not snippet.unsupported],
        [snippet for snippet in snippets if snippet.unsupported],
    )


def report(compilable: list[Snippet], unsupported: list[Snippet]) -> None:
    print(
        f"Compiled {len(compilable)} Kotlin Arc client snippet(s) against Arc.Kotlin source successfully"
        f"{f', skipped {len(unsupported)} unsupported marker(s)' if unsupported else ''}.")
    for snippet in compilable:
        print(f"  - {snippet.identifier} ({SNIPPET_CONTEXTS[snippet.identifier].kind})")
    for snippet in unsupported:
        print(f"  - {snippet.identifier} (unsupported marker, not compiled)")


def run(self_test: bool, dry_run: bool, print_identifier: str | None) -> int:
    compilable, unsupported = collect()
    print(f"Found {len(compilable) + len(unsupported)} client snippet(s) under {display_path(SNIPPET_ROOT)}.")

    corrupt: str | None = None
    if self_test:
        if not compilable:
            raise SnippetError("--self-test needs at least one compilable snippet to plant a defect in")
        corrupt = compilable[0].identifier
        print(f"Self-test: planting a reference to a non-existent type in {corrupt!r}.")
    elif not compilable:
        print(f"All {len(unsupported)} snippet(s) are unsupported markers - nothing to compile.")
        return 0

    written: list[Path] = []
    try:
        written = write_generated(compilable, corrupt=corrupt)

        if print_identifier is not None:
            target = GENERATED_DIR / f"Snippet_{sanitized(print_identifier)}.kt"
            if not target.is_file():
                target = GENERATED_DIR / f"Fixture_{sanitized(print_identifier)}.kt"
            if not target.is_file():
                raise SnippetError(f"Nothing generated for {print_identifier!r}")
            print(f"----- {target.name} -----")
            print(target.read_text(encoding="utf-8"), end="")
            print(f"----- end {target.name} -----")

        if dry_run:
            print(
                "\nDRY RUN: generated "
                f"{len(written)} source file(s) and compiled NOTHING. This is not a verification pass.")
            for path in written:
                print(f"  - {display_path(path)}")
            return 0

        exit_code, _ = compile_generated()
    finally:
        clean_generated(written)

    if self_test:
        if exit_code == 0:
            print(
                "Self-test FAILED: compilation succeeded with a planted reference to a non-existent "
                "type, so this validator is not detecting anything.",
                file=sys.stderr)
            return 1
        print(f"Self-test passed: the planted defect failed compilation (exit code {exit_code}).")
        return 0

    if exit_code != 0:
        print(f"Kotlin snippet compilation failed with exit code {exit_code}.", file=sys.stderr)
        return 1

    report(compilable, unsupported)
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
        help="Print the generated source for one snippet id or fixture name.")
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
            f"BLOCKED: no JVM toolchain - Kotlin client snippets were NOT compiled. {error}",
            file=sys.stderr)
        print(
            "Install a JDK 17 toolchain (CI has one) and re-run "
            "python3 Documentation/validate-client-snippets.py.",
            file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
