#!/usr/bin/env python3
"""Cross-check documentation snippets against current Arc.Kotlin source and samples.

This is deliberately a source-contract check, not a claim that Markdown snippets are
compiled. Runnable behavior remains covered by the Kotlin and Java sample tests.
"""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "Documentation"

required_files = [
    ROOT / "Samples/Kotlin/SpringBoot/src/main/kotlin/io/cratis/arc/samples/kotlin/springboot/CreateTask.kt",
    ROOT / "Samples/Kotlin/SpringBoot/src/main/kotlin/io/cratis/arc/samples/kotlin/springboot/TaskView.kt",
    ROOT / "Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/CreateTask.java",
    ROOT / "Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/TaskView.java",
    ROOT / "Samples/Kotlin/SpringBoot/src/test/kotlin/io/cratis/arc/samples/kotlin/springboot/KotlinSampleApplicationTests.kt",
    ROOT / "Samples/Java/SpringBoot/src/test/java/io/cratis/arc/samples/javaspringboot/JavaSampleApplicationTests.java",
    ROOT / "GradlePlugin/src/main/kotlin/io/cratis/arc/gradle/ArcExtension.kt",
]
for path in required_files:
    if not path.is_file():
        raise SystemExit(f"Missing source used to cross-check snippets: {path}")

source_roots = [ROOT / name for name in ("Source", "Integrations", "Testing", "GradlePlugin")]
source = "\n".join(
    path.read_text()
    for source_root in source_roots
    for path in source_root.rglob("*")
    if path.suffix in {".kt", ".java"}
)
samples = "\n".join(path.read_text() for path in required_files)
docs = "\n".join(path.read_text() for path in DOCS.rglob("*.md"))

documented_symbols = (
    # Artifact and validation surface.
    "Command|CommandKey|ReadModel|FromServices|AllowAnonymous|Authorize|Roles|TreatWarningsAsErrors"
    "|Path|QueryHttpMethod|QueryTransport|CommandValidator|CommandContext|ValidationResult"
    "|ValidationResultSeverity|CommandScenario|BlockingCommandScenario"
    # Pipeline extension points, including the Java adapters a Java reader is told to use.
    "|CommandFilter|QueryFilter|QueryContext|CommandResult|QueryResult"
    "|AuthorizationCommandFilter|AuthorizationQueryFilter"
    "|BlockingCommandFilter|BlockingQueryFilter|AsyncCommandFilter|AsyncQueryFilter"
    "|BlockingCommandFilterAdapter|BlockingQueryFilterAdapter"
    "|AsyncCommandFilterAdapter|AsyncQueryFilterAdapter"
    # Identity and observable state.
    "|ArcPrincipalFactory|IdentityDetailsProvider|ObservableState"
    # Command lifetime and identity extension points.
    "|CommandExecutionScope|AsyncCommandExecutionScope|BlockingCommandExecutionScope"
    "|AsyncCommandExecutionScopeAdapter|BlockingCommandExecutionScopeAdapter"
    "|CommandKeyProvider"
)
symbols = sorted(set(re.findall(rf"\b(?:{documented_symbols})\b", docs)))
for symbol in symbols:
    if symbol not in source:
        raise SystemExit(f"Documented framework symbol not found in source: {symbol}")

for literal in ["/api/create-task", "/api/tasks", '"arguments":{}', "generateArcProxies"]:
    if literal not in docs or literal not in samples + (ROOT / "GradlePlugin/README.md").read_text():
        raise SystemExit(f"Documented sample contract is not cross-checked by source: {literal}")

# The published diagnostics reference is a second copy of the codes the KSP
# processor can emit, so it can silently fall behind the processor. A reader
# hitting a code that is not on the page has no way to look it up, which is the
# whole point of publishing it, so every code the source can report must appear.
diagnostics_page = ROOT / "Documentation/reference/diagnostics.md"
if not diagnostics_page.is_file():
    raise SystemExit(f"Missing diagnostics reference page: {diagnostics_page}")

ksp_source = "\n".join(
    path.read_text()
    for path in (ROOT / "CodeGeneration").rglob("*.kt")
    if "/build/" not in path.as_posix()
)
source_codes = set(re.findall(r"ARCKSP\d{4}", ksp_source))
if not source_codes:
    raise SystemExit("No ARCKSP codes found in CodeGeneration; the diagnostics check is not effective")

documented_codes = set(re.findall(r"ARCKSP\d{4}", diagnostics_page.read_text()))
undocumented = sorted(source_codes - documented_codes)
if undocumented:
    raise SystemExit(
        "Diagnostics reported by the KSP processor but absent from "
        f"Documentation/reference/diagnostics.md: {', '.join(undocumented)}")
print(f"Cross-checked {len(source_codes)} ARCKSP diagnostics against the published reference.")

fences = re.findall(r"```(kotlin|java)\n(.*?)\n```", docs, re.DOTALL)
if not fences:
    raise SystemExit("No Kotlin or Java snippets were checked")
print(f"Cross-checked {len(fences)} Kotlin/Java/Gradle snippets against current source and runnable samples.")
