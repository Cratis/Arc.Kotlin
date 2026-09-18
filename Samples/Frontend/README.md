# Sample frontend

One React application that runs against either plain Arc sample — the Kotlin host or the Java one —
without a single change, because both generate identical routes.

## Run it

Do not start this directly. Start a sample, and it starts this:

```shell
../run.sh                    # Kotlin backend, this frontend on :5173
../run.sh --language java    # the Java backend, same frontend
```

`../run.sh` regenerates the backend's TypeScript proxies, copies them into `src/generated`, and
starts Vite with `/api` and `/.cratis` proxied through to the backend.

:::caution[`src/generated` is build output]
It is gitignored and absent from a fresh clone, so `npm run dev` on its own fails to resolve every
import under `./generated`. That is the intended shape: the client is regenerated from whichever
backend is about to run, so it can never be a build behind the server it talks to. Run `../run.sh`.
:::

## What is in here

| File | What it does |
| --- | --- |
| `src/main.tsx` | Mounts the Arc context and applies credentials when the signed-in user changes. |
| `src/Toolbar.tsx` | Transport, connection count, direct mode, transfer mode, and the sign-in fields. |
| `src/App.tsx` | Page selection. |
| `src/pages/` | One file per showcase feature. |
| `src/authentication.ts` | Encodes the client principal as the header and cookie the sample's backend reads. |

Every page consumes generated proxies only. There is no hand-written URL, request body, or response
type anywhere in this application, which is the thing it exists to demonstrate.

See [the samples overview](../README.md) for what each page shows, and
[the frontend guide](../../Documentation/guides/frontend.md) for the patterns behind them.
