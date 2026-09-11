---
applyTo: "**/*"
---

## Scope boundaries

- The generated client type mapping (`UUID` to `Guid`, `LocalDate` to `DateOnly`, `LocalTime` to
  `TimeOnly`, and the bounded string-keyed map contract) is documented in
  `Documentation/guides/typescript-proxies.md`. Change behavior there and here together; do not
  restate the mapping table in two places that can drift.
- The .NET differential test compares JVM output against a normalized checked-in fixture. It proves
  the JVM renderer has not drifted from that fixture. It does **not** establish raw-output
  compatibility or broader Arc .NET parity — see [arc-parity.md](./arc-parity.md) before claiming
  either.
- Do not add a frontend framework, a bundler, a component library, or a UI test runner to this
  repository. The TypeScript here exists to prove a generated contract, and nothing more.

---
