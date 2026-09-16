---
applyTo: "**/*"
---

## Strict-mode compilation

`:ContractTests:typeScriptBuild` proves the generated clients compile against the real published
packages. It depends on `typeScriptInstall` (`npm ci --ignore-scripts`, so `package-lock.json` is
authoritative), on `verifyContractTestProxyDeterminism` via the prepare tasks, and on the sample
proxy generation, then runs `npm run build` — which is `tsc --noEmit`.

`ContractTests/TypeScript/tsconfig.json` is the contract:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "verbatimModuleSyntax": true,
    "strict": true,
    "noEmit": true,
    "experimentalDecorators": true,
    "useDefineForClassFields": false,
    "forceConsistentCasingInFileNames": true
  },
  "include": ["generated/**/*.ts", "contracts/**/*.ts"]
}
```

`verbatimModuleSyntax` means a type-only import that is emitted as a value import fails compilation.
Generated interfaces and type-only dependencies must use `import type`. The dependencies are pinned
(`@cratis/arc` and `@cratis/arc.react` 22.7.0, `@cratis/fundamentals` 7.18.2, TypeScript 5.9.3);
**do not bump them unless asked** — that is the dependency-manifest rule in
[gradle.md](./gradle.md), and these pins are what make the gate meaningful.
