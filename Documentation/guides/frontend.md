---
title: Use the generated client from React
description: Consume generated Arc commands and queries from a React application, including observable subscriptions, conditional queries, and identity.
---

Generating a TypeScript client is only half the promise. The other half is that the client is
pleasant to use — that a command is a class you construct and execute, that a live query is a hook,
and that neither one makes you write a URL, a request body, or a response type by hand.

This guide is about that half. [TypeScript proxies](typescript-proxies.md) covers producing the
files; everything here assumes you already have them.

## Get the proxies into your application

Generation writes into the directory you point it at:

```kotlin
cratisArc {
    proxies {
        outputDirectory.set(file("../frontend/src/generated"))
    }
}
```

Treat that directory as build output. Do not edit it, do not commit it, and regenerate it whenever
the backend changes — a generated client that is one build behind the server it talks to is worse
than no generated client, because it compiles.

Install the runtime packages the generated code imports:

```shell
npm install @cratis/arc @cratis/arc.react @cratis/fundamentals
```

## Wrap the application once

Everything below needs an `Arc` context. It carries the transport choice, the base path, and the
callback that supplies request headers:

```tsx
import { Arc } from '@cratis/arc.react';

createRoot(document.getElementById('root')!).render(
    <Arc development={true}>
        <App />
    </Arc>
);
```

That is the whole setup for a single-origin application. If your page is served from somewhere other
than the backend — a Vite dev server, typically — read [Serving the page from a dev
server](#serving-the-page-from-a-dev-server) before you go further, because one thing does need
saying out loud.

## Execute a command

A generated command is a class with a `use()` hook. The hook hands back the command instance and a
setter for its properties:

```tsx
import { CreateTask } from './generated/CreateTask';

export const CreateTaskForm = () => {
    const [command, setValues] = CreateTask.use();

    const create = async () => {
        const result = await command.execute();
        if (result.isSuccess) {
            setValues({ title: '' });
        }
    };

    return (
        <>
            <input value={command.title ?? ''} onChange={event => setValues({ title: event.target.value })} />
            <button onClick={create}>Create</button>
        </>
    );
};
```

`execute()` returns the same envelope the HTTP endpoint returns, already typed: `isSuccess`,
`response`, `validationResults`, `isAuthorized`. A rejected command is not an exception — it is a
result you render:

```tsx
const result = await command.execute();
if (!result.isSuccess) {
    setErrors(result.validationResults.map(validation => validation.message));
}
```

Each validation result carries the `members` it applies to, so you can put the message next to the
field that caused it rather than in a banner at the top of the form.

`command.validate()` runs the same validation without the side effect, which is what you want on
blur rather than on submit.

## Read with a query

A query method that returns a plain value or a list generates a one-shot query. `use()` performs it
and re-performs it when its arguments change:

```tsx
import { ById } from './generated/features/queryshowcase/ById';

const [result] = ById.use({ id });
if (result.isPerforming) return <p>Loading…</p>;
return <p>{result.data.name}</p>;
```

`result.hasData` tells you whether anything arrived, `result.isAuthorized` whether the caller was
allowed, and `result.data` is typed from the read model.

## Subscribe to a live query

A query method returning a `Flow` — or a `Flow.Publisher` in Java — generates an observable query.
The hook looks almost identical, and that is the point:

```tsx
import { All } from './generated/features/changestream/All';

const [result] = All.use();
```

There is no polling loop, no refetch after a mutation, no cache to invalidate. A command that
changes the underlying data causes a push, and every component subscribed to that query re-renders.
A change made by another browser tab, or by `curl`, arrives the same way.

Several components calling `All.use()` share one subscription through the query instance cache, so
ten panels on a page cost one connection and one push, not ten.

### React to what changed

When you care about the delta rather than the whole collection, `useChangeStream` reports it:

```tsx
const changes = All.useChangeStream(item => item.id);
// changes.added, changes.replaced, changes.removed
```

The key selector is what makes identity meaningful — without it Arc can only compare whole items.

## Hold a query back until it makes sense

React hooks cannot be called conditionally, which is awkward when a query needs an argument the page
does not have yet. `when(condition)` is the way out:

```tsx
const [result] = ById.when(selectedId !== undefined).use({ id: selectedId });
```

While the condition is false nothing happens: no request is sent and no subscription is opened. This
is not a request whose result is thrown away — it is no request at all. Use it for the query that
needs a selection, and for the query that needs a signed-in user:

```tsx
const identity = useIdentity();
const [result] = Authenticated.when(identity.isSet).use();
```

## Know who is signed in

`useIdentity()` reads what `/.cratis/me` returned — the identity your
[`IdentityDetailsProvider`](security.md) produced, typed:

```tsx
import { useIdentity } from '@cratis/arc.react/identity';

const identity = useIdentity();
identity.isSet;                    // resolved and authenticated
identity.name;
identity.isInRole('administrator');
identity.details;                  // your own details type
```

## Serving the page from a dev server

In development the page usually comes from Vite on one port while Arc runs on another. Proxy Arc's
two path prefixes so the browser still sees a single origin:

```ts
export default defineConfig({
    server: {
        proxy: {
            '/api': { target: 'http://localhost:8080', ws: true },
            '/.cratis': { target: 'http://localhost:8080', ws: true }
        }
    }
});
```

`ws: true` matters on both: observable queries upgrade to a WebSocket under `/.cratis`.

:::caution[Name the dev-server origin on the backend]
Spring refuses a cross-origin WebSocket handshake with `403`, and a browser reports that as a socket
that never opens — no error, no status, just a subscription that stays at "connecting" forever.
Because the page comes from the dev server's origin, every handshake is cross-origin. Tell the
backend to accept it:

```properties
cratis.arc.observable-queries.allowed-origins=http://localhost:5173
```

Leave it empty in a deployment. A WebSocket handshake is not subject to the same-origin policy that
`fetch` obeys, so this check is what stops another site from opening a socket with your visitor's
cookies attached.
:::

## Authenticating a subscription

A one-shot query can carry credentials in a header, because `fetch` can set headers. `EventSource`
and the WebSocket handshake cannot. If your authentication reads a header only, every subscription
will quietly drop to anonymous while ordinary requests keep working — which is a confusing way to
find out.

Make sure whatever produces your `ArcPrincipal` reads a cookie as well as a header. The
[sample's `ArcPrincipalFactory`](https://github.com/Cratis/Arc.Kotlin/blob/main/Samples/Kotlin/SpringBoot/src/main/kotlin/io/cratis/arc/samples/kotlin/springboot/SampleAuthentication.kt)
does exactly that, and is a reasonable shape to copy.

## Changing who you are

Credentials are captured when a query instance is created, and an observable subscription is
authorized once, at handshake. Changing the signed-in user therefore has to reach further than the
next request: remount the `Arc` context and drop the shared connection, or you will keep sending the
previous principal.

The [sample frontend](https://github.com/Cratis/Arc.Kotlin/blob/main/Samples/Frontend/src/main.tsx) shows the whole shape — a `key` on the
`Arc` element, `IdentityProvider.clearIdentityCookie()`, and `resetSharedMultiplexer()`.

## See it running

Every pattern on this page is exercised by a runnable application:

```shell
./Samples/run.sh
```

It starts the Kotlin backend, regenerates the proxies from it, and opens a React frontend against
them. `--language java` runs the same frontend against the Java backend unchanged. See
[the samples](https://github.com/Cratis/Arc.Kotlin/blob/main/Samples/README.md) for the full list of pages and what each one demonstrates.
