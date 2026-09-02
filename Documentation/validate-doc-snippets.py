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

symbols = sorted(set(re.findall(r"\b(?:Command|CommandKey|ReadModel|FromServices|AllowAnonymous|Authorize|Roles|TreatWarningsAsErrors|Path|QueryHttpMethod|QueryTransport|CommandValidator|CommandContext|ValidationResult|ValidationResultSeverity|CommandScenario|BlockingCommandScenario)\b", docs)))
for symbol in symbols:
    if symbol not in source:
        raise SystemExit(f"Documented framework symbol not found in source: {symbol}")

for literal in ["/api/create-task", "/api/tasks", '"arguments":{}', "generateArcProxies"]:
    if literal not in docs or literal not in samples + (ROOT / "GradlePlugin/README.md").read_text():
        raise SystemExit(f"Documented sample contract is not cross-checked by source: {literal}")

fences = re.findall(r"```(kotlin|java)\n(.*?)\n```", docs, re.DOTALL)
if not fences:
    raise SystemExit("No Kotlin or Java snippets were checked")
print(f"Cross-checked {len(fences)} Kotlin/Java/Gradle snippets against current source and runnable samples.")
