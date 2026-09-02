// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import test from "node:test";
import { validateTapSummary, waitForPort } from "./run-runtime-gate.mjs";

function tapSummary({ tests, pass, fail, cancelled, skipped, todo }) {
    return `TAP version 13
1..${tests}
# tests ${tests}
# suites 0
# pass ${pass}
# fail ${fail}
# cancelled ${cancelled}
# skipped ${skipped}
# todo ${todo}
# duration_ms 1
`;
}

test("accepts an exact successful TAP summary", () => {
    const summary = validateTapSummary(
        tapSummary({
            tests: 2,
            pass: 2,
            fail: 0,
            cancelled: 0,
            skipped: 0,
            todo: 0,
        }),
        2,
        "test run",
    );

    assert.deepEqual(summary, {
        tests: 2,
        pass: 2,
        fail: 0,
        cancelled: 0,
        skipped: 0,
        todo: 0,
    });
});

test("rejects a TAP summary containing a skipped test", () => {
    assert.throws(
        () =>
            validateTapSummary(
                tapSummary({
                    tests: 2,
                    pass: 1,
                    fail: 0,
                    cancelled: 0,
                    skipped: 1,
                    todo: 0,
                }),
                2,
                "test run",
            ),
        /pass expected 2 but observed 1; skipped expected 0 but observed 1/,
    );
});

test("rejects a missing TAP summary field", () => {
    const missingTodo = tapSummary({
        tests: 2,
        pass: 2,
        fail: 0,
        cancelled: 0,
        skipped: 0,
        todo: 0,
    }).replace("# todo 0\n", "");

    assert.throws(
        () => validateTapSummary(missingTodo, 2, "test run"),
        /Missing Node TAP summary fields: todo/,
    );
});

test("rejects an observed test-count mismatch", () => {
    assert.throws(
        () =>
            validateTapSummary(
                tapSummary({
                    tests: 1,
                    pass: 1,
                    fail: 0,
                    cancelled: 0,
                    skipped: 0,
                    todo: 0,
                }),
                2,
                "test run",
            ),
        /tests expected 2 but observed 1; pass expected 2 but observed 1/,
    );
});

test("rejects a Spring spawn error and removes startup listeners", async () => {
    const child = spawn(
        `/definitely-not-an-arc-kotlin-java-${process.pid}`,
        [],
        { stdio: ["ignore", "pipe", "pipe"] },
    );

    await assert.rejects(
        waitForPort(child, 1_000, () => {}),
        /The Kotlin Spring Boot sample failed to start:.*ENOENT/,
    );
    assert.equal(child.listenerCount("error"), 0);
    assert.equal(child.listenerCount("exit"), 0);
    assert.equal(child.stdout.listenerCount("data"), 0);
    assert.equal(child.stderr.listenerCount("data"), 0);
});
