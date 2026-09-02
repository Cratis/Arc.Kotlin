// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import assert from "node:assert/strict";
import { test } from "node:test";
import { EventSource as NodeEventSource } from "eventsource";
import { Globals } from "@cratis/arc";
import { IdentityProvider } from "@cratis/arc/identity";
import {
    Paging,
    QueryHttpMethod,
    type QueryResult,
    QueryTransportMethod,
    resetSharedMultiplexer,
    SortDirection,
    Sorting,
} from "@cratis/arc/queries";
import {
    All,
    ById,
    CompleteTask,
    CreateTask,
    CreateTaskBatch,
    Current,
    EchoMaps,
    MapView,
    Observe,
    TaskCreated,
    TaskView,
} from "../generated/runtime";

const origin = process.env.ARC_KOTLIN_SAMPLE_ORIGIN;
if (!origin) {
    throw new Error(
        "ARC_KOTLIN_SAMPLE_ORIGIN must identify the running Kotlin Spring Boot sample.",
    );
}

interface HttpExchange {
    method: string;
    requestCorrelationId: string | null;
    responseCorrelationId: string | null;
    url: string;
}

const exchanges: HttpExchange[] = [];
const originalFetch = globalThis.fetch;
globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    const response = await originalFetch(input, init);
    exchanges.push({
        method: request.method,
        requestCorrelationId: request.headers.get("X-Correlation-ID"),
        responseCorrelationId: response.headers.get("X-Correlation-ID"),
        url: request.url,
    });
    return response;
}) as typeof fetch;

Globals.origin = origin;
Globals.apiBasePath = "";
Globals.queryHttpMethod = QueryHttpMethod.Get;
Globals.queryDirectMode = false;
Globals.queryTransportMethod = QueryTransportMethod.WebSocket;
Globals.queryCacheRetentionMs = 0;
// SAFETY: the Node eventsource implementation provides the browser EventSource surface used by Arc.
Globals.eventSourceFactory = (url) =>
    new NodeEventSource(url) as unknown as EventSource;
IdentityProvider.setOrigin(origin);
IdentityProvider.setApiBasePath("");

function uuid(): string {
    return crypto.randomUUID();
}

function exchangeFor(correlationId: string): HttpExchange {
    const exchange = [...exchanges]
        .reverse()
        .find((candidate) => candidate.requestCorrelationId === correlationId);
    assert.ok(
        exchange,
        `Expected an HTTP exchange for correlation ${correlationId}.`,
    );
    return exchange;
}

async function createTask(title: string): Promise<TaskCreated> {
    const command = new CreateTask();
    command.title = title;
    const result = await command.execute();
    assert.equal(result.isSuccess, true, result.exceptionMessages.join("\n"));
    assert.ok(result.response instanceof TaskCreated);
    return result.response;
}

async function observeOnce(configure: () => void): Promise<TaskView[]> {
    configure();
    const query = new Observe();
    return new Promise<TaskView[]>((resolve, reject) => {
        const timer = setTimeout(
            () =>
                reject(
                    new Error(
                        "Observable proxy did not receive a result in time.",
                    ),
                ),
            8_000,
        );
        query.subscribe((result: QueryResult<TaskView[]>) => {
            clearTimeout(timer);
            query.dispose();
            if (!result.isSuccess) {
                reject(
                    new Error(
                        `Observable query failed: ${result.exceptionMessages.join(", ")}`,
                    ),
                );
                return;
            }
            assert.ok(
                result.data.every((task: TaskView) => task instanceof TaskView),
            );
            resolve(result.data);
        });
    });
}

test("published TypeScript proxies interoperate with the Kotlin Spring Boot runtime", async (t) => {
    await t.test(
        "validate rejects invalid commands without invoking the handler",
        async () => {
            const command = new CreateTask();
            command.title = "   ";
            const result = await command.validate();

            assert.equal(result.isSuccess, false);
            assert.equal(result.isValid, false);
            assert.equal(
                result.validationResults[0]?.message,
                "A task title is required.",
            );
            assert.deepEqual(result.validationResults[0]?.members, ["title"]);

            const all = await new All().perform();
            assert.equal(all.isSuccess, true);
            assert.deepEqual(all.data, []);
        },
    );

    let created = new TaskCreated();
    await t.test(
        "execute returns the generated typed response and echoes correlation",
        async () => {
            const correlationId = uuid();
            const command = new CreateTask();
            command.title = "Zulu runtime proxy";
            command.setHttpHeadersCallback(() => ({
                "X-Correlation-ID": correlationId,
            }));

            const result = await command.execute();
            assert.equal(
                result.isSuccess,
                true,
                result.exceptionMessages.join("\n"),
            );
            assert.ok(result.response instanceof TaskCreated);
            assert.equal(result.response.title, "Zulu runtime proxy");
            assert.equal(result.correlationId.toString(), correlationId);
            assert.match(result.response.id, /^[0-9a-f-]{36}$/i);

            const exchange = exchangeFor(correlationId);
            assert.equal(exchange.method, "POST");
            assert.equal(exchange.responseCorrelationId, correlationId);
            created = result.response;
        },
    );

    await t.test(
        "malformed command bodies return the safe protocol envelope",
        async () => {
            const correlationId = uuid();
            const response = await originalFetch(`${origin}/api/create-task`, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "X-Correlation-ID": correlationId,
                },
                body: "{",
            });
            const result = (await response.json()) as {
                isSuccess: boolean;
                validationResults: Array<{ reason: string }>;
                exceptionMessages: string[];
            };

            assert.equal(response.status, 400);
            assert.equal(
                response.headers.get("X-Correlation-ID"),
                correlationId,
            );
            assert.equal(result.isSuccess, false);
            assert.equal(
                result.validationResults[0]?.reason,
                "malformedRequest",
            );
            assert.deepEqual(result.exceptionMessages, []);
        },
    );

    await t.test("GET query returns generated model instances", async () => {
        const correlationId = uuid();
        const query = new ById();
        query.setHttpHeadersCallback(() => ({
            "X-Correlation-ID": correlationId,
        }));
        const result = await query.perform({ id: created.id });

        assert.equal(
            result.isSuccess,
            true,
            result.exceptionMessages.join("\n"),
        );
        assert.ok(result.data instanceof TaskView);
        assert.equal(result.data.id, created.id);
        assert.equal(result.data.title, created.title);

        const exchange = exchangeFor(correlationId);
        assert.equal(exchange.method, "GET");
        assert.equal(exchange.responseCorrelationId, correlationId);
    });

    await t.test(
        "provide-to-handle command returns a hydrated completed task",
        async () => {
            const command = new CompleteTask();
            command.taskId = created.id;
            const result = await command.execute();

            assert.equal(
                result.isSuccess,
                true,
                result.exceptionMessages.join("\n"),
            );
            assert.ok(result.response instanceof TaskView);
            assert.equal(result.response.id, created.id);
            assert.equal(result.response.title, created.title);
            assert.equal(result.response.completed, true);

            const query = new ById();
            const queried = await query.perform({ id: created.id });
            assert.ok(queried.data instanceof TaskView);
            assert.equal(queried.data.completed, true);
        },
    );

    await t.test(
        "map command and GET query hydrate typed models with recursive object records",
        async () => {
            const command = new EchoMaps();
            command.strings = { language: "typescript" };
            command.numbers = { values: [1, 2] };
            command.nested = { flags: { ready: true } };
            command.optional = undefined;

            assert.equal(command.propertyDescriptors[0]?.type, Object);
            const commandResult = await command.execute();
            assert.equal(
                commandResult.isSuccess,
                true,
                commandResult.exceptionMessages.join("\n"),
            );
            assert.ok(commandResult.response instanceof MapView);
            assert.deepEqual(commandResult.response.strings, {
                language: "typescript",
            });
            assert.deepEqual(commandResult.response.numbers, {
                values: [1, 2],
            });
            assert.deepEqual(commandResult.response.nested, {
                flags: { ready: true },
            });
            assert.equal(commandResult.response.optional, undefined);
            assert.equal("_entries" in commandResult.response.strings, false);

            const queryResult = await new Current().perform();
            assert.equal(
                queryResult.isSuccess,
                true,
                queryResult.exceptionMessages.join("\n"),
            );
            assert.ok(queryResult.data instanceof MapView);
            assert.deepEqual(queryResult.data.strings, { source: "query" });
            assert.deepEqual(queryResult.data.numbers, { values: [1, 2] });
            assert.deepEqual(queryResult.data.nested, {
                flags: { ready: true },
            });
            assert.equal(queryResult.data.optional, undefined);
        },
    );

    await t.test(
        "generated map setters reject reserved keys and unsafe prototypes locally",
        async () => {
            const command = new EchoMaps();

            assert.throws(() => {
                command.strings = JSON.parse('{"__proto__":{"polluted":true}}');
            }, /reserved key '__proto__'/);
            assert.throws(() => {
                // SAFETY: The test intentionally bypasses the string value type to exercise prototype rejection.
                command.strings = {
                    __proto__: { polluted: true },
                    safe: "value",
                } as unknown as Record<string, string>;
            }, /must use Object\.prototype or a null prototype/);
            assert.throws(() => {
                // SAFETY: The test intentionally injects an object into a number sequence to verify recursive guards.
                command.numbers = {
                    values: [JSON.parse('{"constructor":1}')],
                } as unknown as Record<string, number[]>;
            }, /reserved key 'constructor'/);
            assert.throws(() => {
                command.nested = {
                    safe: JSON.parse('{"prototype":true}'),
                };
            }, /reserved key 'prototype'/);

            const strings: Record<string, string> = Object.create(null);
            strings.language = "null-prototype";
            const nested: Record<
                string,
                Record<string, boolean>
            > = Object.create(null);
            nested.flags = { ready: true };
            command.strings = strings;
            command.numbers = { values: [1, 2] };
            command.nested = nested;
            command.optional = { constructor_: "near-miss" };

            const result = await command.execute();

            assert.equal(
                result.isSuccess,
                true,
                result.exceptionMessages.join("\n"),
            );
            assert.deepEqual(result.response?.strings, {
                language: "null-prototype",
            });
            assert.deepEqual(result.response?.nested, {
                flags: { ready: true },
            });
            assert.deepEqual(result.response?.optional, {
                constructor_: "near-miss",
            });
        },
    );

    await t.test(
        "RFC QUERY query uses a JSON envelope and echoes correlation",
        async () => {
            const correlationId = uuid();
            const query = new All();
            query.setHttpMethod(QueryHttpMethod.Query);
            query.setHttpHeadersCallback(() => ({
                "X-Correlation-ID": correlationId,
            }));
            const result = await query.perform();

            assert.equal(
                result.isSuccess,
                true,
                result.exceptionMessages.join("\n"),
            );
            assert.ok(result.data[0] instanceof TaskView);
            assert.equal(result.data[0]?.id, created.id);

            const exchange = exchangeFor(correlationId);
            assert.equal(exchange.method, "QUERY");
            assert.equal(exchange.responseCorrelationId, correlationId);
        },
    );

    await t.test(
        "paging and sorting are honored through the generated query",
        async () => {
            await createTask("Alpha runtime proxy");
            await createTask("Charlie runtime proxy");
            await createTask("Bravo runtime proxy");

            const query = new All();
            query.setHttpMethod(QueryHttpMethod.Query);
            query.paging = new Paging(0, 2);
            query.sorting = new Sorting("title", SortDirection.descending);
            const result = await query.perform();

            assert.equal(
                result.isSuccess,
                true,
                result.exceptionMessages.join("\n"),
            );
            assert.deepEqual(
                result.data.map((task: TaskView) => task.title),
                ["Zulu runtime proxy", "Charlie runtime proxy"],
            );
            assert.equal(result.paging.page, 0);
            assert.equal(result.paging.size, 2);
            assert.equal(result.paging.totalItems, 4);
            assert.equal(result.paging.totalPages, 2);
        },
    );

    await t.test(
        "identity uses the sample-only provider through the published client",
        async () => {
            const identity = await IdentityProvider.refresh<{
                source: string;
            }>();

            assert.equal(identity.isSet, true);
            assert.equal(identity.id, "arc-kotlin-runtime-gate");
            assert.equal(identity.name, "Arc Kotlin Runtime Gate");
            assert.equal(identity.isInRole("sample"), true);
            assert.deepEqual(identity.details, { source: "Arc.Kotlin sample" });
        },
    );

    await t.test(
        "enumerable command returns generated model instances and echoes correlation",
        async () => {
            const correlationId = uuid();
            const command = new CreateTaskBatch();
            command.titles = [
                "First live batch task",
                "Second live batch task",
            ];
            command.setHttpHeadersCallback(() => ({
                "X-Correlation-ID": correlationId,
            }));

            assert.equal(command._responseType, TaskCreated);
            assert.equal(command._isResponseTypeEnumerable, true);

            const result = await command.execute();
            assert.equal(
                result.isSuccess,
                true,
                result.exceptionMessages.join("\n"),
            );
            assert.ok(result.response);
            const responses: TaskCreated[] = result.response;
            assert.ok(Array.isArray(responses));
            assert.equal(responses.length, 2);
            assert.ok(
                responses.every((response) => response instanceof TaskCreated),
            );
            assert.deepEqual(
                responses.map((response) => response.title),
                ["First live batch task", "Second live batch task"],
            );
            assert.ok(
                responses.every((response) =>
                    /^[0-9a-f-]{36}$/i.test(response.id),
                ),
            );
            assert.equal(
                new Set(responses.map((response) => response.id)).size,
                responses.length,
            );
            assert.equal(result.correlationId.toString(), correlationId);

            const exchange = exchangeFor(correlationId);
            assert.equal(exchange.method, "POST");
            assert.equal(exchange.responseCorrelationId, correlationId);
        },
    );

    await t.test(
        "observable query uses the default multiplexed WebSocket transport",
        async () => {
            resetSharedMultiplexer();
            const tasks = await observeOnce(() => {
                Globals.queryTransportMethod = QueryTransportMethod.WebSocket;
                Globals.queryDirectMode = false;
            });
            assert.equal(tasks.length, 6);
        },
    );

    await t.test(
        "observable query uses the direct WebSocket transport",
        async () => {
            resetSharedMultiplexer();
            const tasks = await observeOnce(() => {
                Globals.queryTransportMethod = QueryTransportMethod.WebSocket;
                Globals.queryDirectMode = true;
            });
            assert.equal(tasks.length, 6);
        },
    );

    await t.test("observable query uses the direct SSE transport", async () => {
        resetSharedMultiplexer();
        const tasks = await observeOnce(() => {
            Globals.queryTransportMethod =
                QueryTransportMethod.ServerSentEvents;
            Globals.queryDirectMode = true;
        });
        assert.equal(tasks.length, 6);
    });
});
