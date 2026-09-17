// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import assert from "node:assert/strict";
import { test } from "node:test";
import { Globals } from "@cratis/arc";
import { QueryHttpMethod } from "@cratis/arc/queries";
import { DateOnly, Guid, TimeOnly } from "@cratis/fundamentals";
import {
    CalendarEcho,
    EchoCalendar,
    FindCalendarDefaultGet,
    FindCalendarDefaultQuery,
    FindCalendarEcho,
    FindCalendarPrecision,
} from "../generated/runtime/index.js";

const origin = process.env.ARC_KOTLIN_SAMPLE_ORIGIN;
if (!origin) {
    throw new Error(
        "ARC_KOTLIN_SAMPLE_ORIGIN must identify the running Kotlin Spring Boot sample.",
    );
}

const expectedDate = "2026-01-01";
const expectedTime = "14:30:45.123";
const expectedIdentifier = "11111111-1111-1111-1111-111111111111";
const precisionServerTime = "08:09:10.1235567";
const precisionClientTime = "08:09:10.123";

interface HttpExchange {
    body: string | null;
    method: string;
    requestCorrelationId: string | null;
    responseCorrelationId: string | null;
    url: string;
}

const exchanges: HttpExchange[] = [];
const originalFetch = globalThis.fetch;
globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    const body =
        request.method === "GET" || request.method === "HEAD"
            ? null
            : await request.clone().text();
    const response = await originalFetch(request);
    exchanges.push({
        body,
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

function createValues(): {
    date: DateOnly;
    identifier: Guid;
    time: TimeOnly;
} {
    return {
        date: DateOnly.from(2026, 1, 1),
        identifier: Guid.parse(expectedIdentifier),
        time: TimeOnly.from(14, 30, 45, 123),
    };
}

function exchangeFor(correlationId: string): HttpExchange {
    for (let index = exchanges.length - 1; index >= 0; index -= 1) {
        const exchange = exchanges[index];
        if (exchange.requestCorrelationId === correlationId) return exchange;
    }
    assert.fail(`Expected an HTTP exchange for correlation ${correlationId}.`);
}

function assertCalendarEcho(actual: CalendarEcho): void {
    assert.ok(actual instanceof CalendarEcho);
    assert.ok(actual.date instanceof DateOnly);
    assert.ok(actual.time instanceof TimeOnly);
    assert.ok(actual.identifier instanceof Guid);

    assert.equal(actual.date.toString(), expectedDate);
    assert.deepEqual(
        [actual.date.year, actual.date.month, actual.date.day],
        [2026, 1, 1],
    );
    assert.equal(actual.date.day, 1, "The boundary date must not shift a day.");
    assert.equal(actual.time.toString(), expectedTime);
    assert.deepEqual(
        [
            actual.time.hour,
            actual.time.minute,
            actual.time.second,
            actual.time.millisecond,
        ],
        [14, 30, 45, 123],
    );
    assert.equal(actual.time.equals(TimeOnly.from(14, 30, 45, 123)), true);
    assert.equal(actual.identifier.toString(), expectedIdentifier);
    assert.equal(
        actual.identifier.equals(Guid.parse(expectedIdentifier)),
        true,
    );
}

test("calendar command serializes scalar strings and hydrates its generated response", async () => {
    const correlationId = crypto.randomUUID();
    const values = createValues();
    const command = new EchoCalendar();
    command.identifier = values.identifier;
    command.date = values.date;
    command.time = values.time;
    command.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": correlationId,
    }));

    const result = await command.execute();

    assert.equal(result.isSuccess, true, result.exceptionMessages.join("\n"));
    assert.ok(result.response);
    assertCalendarEcho(result.response);
    assert.equal(result.correlationId.toString(), correlationId);

    const exchange = exchangeFor(correlationId);
    assert.equal(exchange.method, "POST");
    assert.equal(exchange.responseCorrelationId, correlationId);
    assert.ok(exchange.body);
    let parsedBody: unknown;
    try {
        parsedBody = JSON.parse(exchange.body);
    } catch (error) {
        assert.fail(`Expected a JSON command body: ${error}`);
    }
    assert.deepEqual(parsedBody, {
        identifier: expectedIdentifier,
        date: expectedDate,
        time: expectedTime,
    });
});

test("calendar GET query preserves encoded values and hydrates its generated model", async () => {
    const correlationId = crypto.randomUUID();
    const values = createValues();
    const query = new FindCalendarEcho();
    query.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": correlationId,
    }));

    const result = await query.perform(values);

    assert.equal(result.isSuccess, true, result.exceptionMessages.join("\n"));
    assertCalendarEcho(result.data);

    const exchange = exchangeFor(correlationId);
    assert.equal(exchange.method, "GET");
    assert.equal(exchange.body, null);
    assert.equal(exchange.responseCorrelationId, correlationId);
    assert.ok(
        exchange.url.includes(`date=${encodeURIComponent(expectedDate)}`),
    );
    assert.ok(
        exchange.url.includes(
            `identifier=${encodeURIComponent(expectedIdentifier)}`,
        ),
    );
    assert.ok(
        exchange.url.includes(`time=${encodeURIComponent(expectedTime)}`),
    );
});

test("defaulted GET query omits absent arguments and sends supplied overrides", async () => {
    const absentCorrelationId = crypto.randomUUID();
    const absent = new FindCalendarDefaultGet();
    absent.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": absentCorrelationId,
    }));

    const absentResult = await absent.perform();

    assert.equal(absentResult.isSuccess, true);
    assert.equal(absentResult.data.date.toString(), "2026-01-01");
    const absentExchange = exchangeFor(absentCorrelationId);
    assert.equal(absentExchange.method, "GET");
    assert.equal(absentExchange.responseCorrelationId, absentCorrelationId);
    assert.equal(new URL(absentExchange.url).searchParams.has("year"), false);

    const suppliedCorrelationId = crypto.randomUUID();
    const supplied = new FindCalendarDefaultGet();
    supplied.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": suppliedCorrelationId,
    }));

    const suppliedResult = await supplied.perform({ year: 2030 });

    assert.equal(suppliedResult.isSuccess, true);
    assert.equal(suppliedResult.data.date.toString(), "2030-01-01");
    const suppliedExchange = exchangeFor(suppliedCorrelationId);
    assert.equal(
        new URL(suppliedExchange.url).searchParams.get("year"),
        "2030",
    );
});

test("defaulted RFC QUERY omits absent arguments and sends supplied overrides", async () => {
    const absentCorrelationId = crypto.randomUUID();
    const absent = new FindCalendarDefaultQuery();
    absent.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": absentCorrelationId,
    }));

    const absentResult = await absent.perform();

    assert.equal(absentResult.isSuccess, true);
    assert.equal(absentResult.data.date.toString(), "2027-01-01");
    const absentExchange = exchangeFor(absentCorrelationId);
    assert.equal(absentExchange.method, "QUERY");
    assert.equal(absentExchange.responseCorrelationId, absentCorrelationId);
    assert.ok(absentExchange.body);
    assert.deepEqual(JSON.parse(absentExchange.body), { arguments: {} });

    const suppliedCorrelationId = crypto.randomUUID();
    const supplied = new FindCalendarDefaultQuery();
    supplied.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": suppliedCorrelationId,
    }));

    const suppliedResult = await supplied.perform({ year: 2031 });

    assert.equal(suppliedResult.isSuccess, true);
    assert.equal(suppliedResult.data.date.toString(), "2031-01-01");
    const suppliedExchange = exchangeFor(suppliedCorrelationId);
    assert.ok(suppliedExchange.body);
    const suppliedBody = JSON.parse(suppliedExchange.body) as {
        arguments?: Record<string, unknown>;
    };
    assert.deepEqual(suppliedBody.arguments, { year: 2031 });
});

test("[characterization — #186 / Cratis/Fundamentals#1123] RFC QUERY body serialises DateOnly and TimeOnly as component objects, not ISO strings; GET path serialises them correctly for contrast", async () => {
    // CHARACTERIZATION TEST — this documents a known defect.
    //
    // DateOnly and TimeOnly in @cratis/fundamentals 7.18.4 have no toJSON() method, so when an
    // RFC QUERY body is built with JSON.stringify(), these types are serialised as plain component
    // objects ({year, month, day} and {hour, minute, second, millisecond}) instead of ISO strings.
    // The GET path is unaffected because UrlHelpers.buildQueryParams() uses String(value), which
    // calls toString() and produces the correct ISO string.
    //
    // Upstream fix: Cratis/Fundamentals#1123.  Local tracking: #186.
    //
    // WHEN THIS TEST STARTS FAILING: @cratis/fundamentals has added toJSON() to DateOnly and
    // TimeOnly (following the Guid precedent).  At that point:
    //   1. Replace the assert.deepEqual assertions below with:
    //        assert.equal(parsedBody.arguments?.date, "2026-01-01");
    //        assert.equal(parsedBody.arguments?.time, "14:30:45.123");
    //   2. Bump the @cratis/fundamentals pin in ContractTests/TypeScript/package.json.
    //   3. Remove this comment block and the "[characterization — #186 / ...]" prefix from the
    //      test name.
    //
    // Do NOT fix this by:
    //   - Making the JVM parse component objects for DateOnly/TimeOnly parameters.
    //   - Forcing the generated QUERY-declared query to use GET (hides the contract break).

    const values = createValues();

    // ── GET path: correct ISO-string serialisation (for contrast) ────────────────────────────
    const getCorrelationId = crypto.randomUUID();
    const getQuery = new FindCalendarEcho();
    getQuery.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": getCorrelationId,
    }));
    // Globals.queryHttpMethod is QueryHttpMethod.Get, so no override is needed here.
    await getQuery.perform(values);

    const getExchange = exchangeFor(getCorrelationId);
    assert.equal(getExchange.method, "GET");
    assert.equal(getExchange.body, null);
    assert.ok(
        getExchange.url.includes(`date=${encodeURIComponent(expectedDate)}`),
        "GET must encode DateOnly as an ISO string in the URL query string.",
    );
    assert.ok(
        getExchange.url.includes(`time=${encodeURIComponent(expectedTime)}`),
        "GET must encode TimeOnly as an ISO string in the URL query string.",
    );
    assert.ok(
        getExchange.url.includes(
            `identifier=${encodeURIComponent(expectedIdentifier)}`,
        ),
        "GET must encode Guid as an ISO string in the URL query string.",
    );

    // ── QUERY path: broken component-object serialisation (characterised) ─────────────────────
    const queryCorrelationId = crypto.randomUUID();
    const rfcQuery = new FindCalendarEcho();
    rfcQuery.setHttpMethod(QueryHttpMethod.Query);
    rfcQuery.setHttpHeadersCallback(() => ({
        "X-Correlation-ID": queryCorrelationId,
    }));
    // The /api/calendar-echo endpoint may reject the QUERY verb with 405; that is expected and
    // irrelevant — this test inspects only the outgoing request body, not the server response.
    await rfcQuery.perform(values);

    const queryExchange = exchangeFor(queryCorrelationId);
    assert.equal(queryExchange.method, "QUERY");
    assert.ok(
        queryExchange.body,
        "Expected a non-null request body for an RFC QUERY request.",
    );
    const parsedBody = JSON.parse(queryExchange.body) as {
        arguments?: Record<string, unknown>;
    };

    // CHARACTERIZATION: DateOnly has no toJSON(); JSON.stringify serialises {year, month, day}.
    // Replace with assert.equal(parsedBody.arguments?.date, "2026-01-01") once upstream adds toJSON().
    assert.deepEqual(
        parsedBody.arguments?.date,
        { year: 2026, month: 1, day: 1 },
        "CHARACTERIZATION: DateOnly must serialise as a component object until @cratis/fundamentals adds toJSON().",
    );
    // CHARACTERIZATION: TimeOnly has no toJSON(); JSON.stringify serialises {hour, minute, second, millisecond}.
    // Replace with assert.equal(parsedBody.arguments?.time, "14:30:45.123") once upstream adds toJSON().
    assert.deepEqual(
        parsedBody.arguments?.time,
        { hour: 14, minute: 30, second: 45, millisecond: 123 },
        "CHARACTERIZATION: TimeOnly must serialise as a component object until @cratis/fundamentals adds toJSON().",
    );
    // Guid already has toJSON() and is unaffected by this defect.
    assert.equal(
        parsedBody.arguments?.identifier,
        expectedIdentifier,
        "Guid must serialise as an ISO string because it already has toJSON().",
    );
});

test("shared client TimeOnly millisecond truncation is not an exact precision round-trip", async () => {
    const query = new FindCalendarPrecision();
    const rawResponse = await originalFetch(new URL(query.route, origin));

    assert.equal(rawResponse.status, 200);
    const rawPayload = (await rawResponse.json()) as {
        data?: { time?: unknown };
    };
    assert.equal(
        rawPayload.data?.time,
        precisionServerTime,
        "The JVM wire must retain all seven fractional digits.",
    );

    const result = await query.perform();

    assert.equal(result.isSuccess, true, result.exceptionMessages.join("\n"));
    assert.ok(result.data instanceof CalendarEcho);
    assert.ok(result.data.time instanceof TimeOnly);
    assert.equal(result.data.time.millisecond, 123);
    assert.equal(result.data.time.toString(), precisionClientTime);
});
