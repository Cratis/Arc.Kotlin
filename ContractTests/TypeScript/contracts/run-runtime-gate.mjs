// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import { spawn } from "node:child_process";
import { resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const startupTimeoutMs = 90_000;
const gateTimeoutMs = 240_000;
const capturedTapOutputLimit = 65_536;
const tapSummaryFields = [
    "tests",
    "pass",
    "fail",
    "cancelled",
    "skipped",
    "todo",
];
const childRuns = [
    {
        label: "calendar runtime contract [UTC]",
        contract: "contracts/runtime.calendar.contract.ts",
        timeZone: "UTC",
        expectedTests: 5,
    },
    {
        label: "calendar runtime contract [America/Los_Angeles]",
        contract: "contracts/runtime.calendar.contract.ts",
        timeZone: "America/Los_Angeles",
        expectedTests: 5,
    },
    {
        label: "general runtime contract [UTC]",
        contract: "contracts/runtime.contract.ts",
        timeZone: "UTC",
        expectedTests: 15,
    },
];

let application;
let activeTestRunner;
let gateTimer;
let stopPromise;
let interrupted = false;
let timedOut = false;

function report(message = "") {
    process.stdout.write(`${message}\n`);
}

function reportError(message) {
    process.stderr.write(`${String(message)}\n`);
}

function terminate(child, signal) {
    if (!child?.pid || child.exitCode !== null) return;
    if (process.platform !== "win32") {
        try {
            process.kill(-child.pid, signal);
            return;
        } catch {
            // The process may have exited between the state check and the signal.
        }
    }
    try {
        child.kill(signal);
    } catch {
        // The process may have exited between the state check and the signal.
    }
}

function waitForExit(child, timeoutMs) {
    if (!child || child.exitCode !== null) return Promise.resolve();
    return new Promise((resolveExit) => {
        let settled = false;
        const finish = () => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            child.off("exit", finish);
            child.off("close", finish);
            resolveExit();
        };
        const timer = setTimeout(finish, timeoutMs);
        child.once("exit", finish);
        child.once("close", finish);
    });
}

function stopEverything() {
    if (stopPromise) return stopPromise;
    stopPromise = (async () => {
        clearTimeout(gateTimer);
        terminate(activeTestRunner, "SIGTERM");
        terminate(application, "SIGTERM");
        await Promise.all([
            waitForExit(activeTestRunner, 5_000),
            waitForExit(application, 10_000),
        ]);
        terminate(activeTestRunner, "SIGKILL");
        terminate(application, "SIGKILL");
    })();
    return stopPromise;
}

export function waitForPort(
    child,
    timeoutMs = startupTimeoutMs,
    writeOutput = (text) => process.stdout.write(text),
) {
    return new Promise((resolvePort, rejectPort) => {
        let settled = false;
        let output = "";

        const removeStartupListeners = (removeForwardingListeners) => {
            child.off("error", onError);
            child.off("exit", onExit);
            child.stdout?.off("data", inspect);
            child.stderr?.off("data", inspect);
            if (removeForwardingListeners) {
                child.stdout?.off("data", forwardOutput);
                child.stderr?.off("data", forwardOutput);
            }
        };
        const succeed = (port) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            removeStartupListeners(false);
            output = "";
            resolvePort(port);
        };
        const fail = (error) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            removeStartupListeners(true);
            rejectPort(error);
        };
        const forwardOutput = (chunk) => writeOutput(chunk.toString());
        const inspect = (chunk) => {
            const text = chunk.toString();
            output = `${output}${text}`.slice(-32_768);
            const match = output.match(/Tomcat started on port (\d+)/);
            if (match) succeed(Number(match[1]));
        };
        const onError = (error) => {
            fail(
                new Error(
                    `The Kotlin Spring Boot sample failed to start: ${error.message}`,
                    { cause: error },
                ),
            );
        };
        const onExit = (code) => {
            fail(
                new Error(
                    `The Kotlin Spring Boot sample exited before startup (code ${code ?? "unknown"}).`,
                ),
            );
        };
        const timer = setTimeout(() => {
            fail(
                new Error(
                    "The Kotlin Spring Boot sample did not start in time.",
                ),
            );
        }, timeoutMs);

        child.stdout?.on("data", forwardOutput);
        child.stderr?.on("data", forwardOutput);
        child.stdout?.on("data", inspect);
        child.stderr?.on("data", inspect);
        child.once("error", onError);
        child.once("exit", onExit);
    });
}

export function parseTapSummary(output) {
    const summary = {};
    const summaryLine = /^# (tests|pass|fail|cancelled|skipped|todo)(?:\s|$)/;
    const validSummaryLine =
        /^# (tests|pass|fail|cancelled|skipped|todo) ([0-9]+)$/;

    for (const line of output.split(/\r?\n/)) {
        const candidate = line.match(summaryLine);
        if (!candidate) continue;
        const parsed = line.match(validSummaryLine);
        if (!parsed) {
            throw new Error(
                `Malformed Node TAP summary line for ${candidate[1]}: ${JSON.stringify(line)}.`,
            );
        }
        const [, field, valueText] = parsed;
        if (Object.hasOwn(summary, field)) {
            throw new Error(`Duplicate Node TAP summary field: ${field}.`);
        }
        const value = Number(valueText);
        if (!Number.isSafeInteger(value)) {
            throw new Error(
                `Node TAP summary field ${field} is not a safe integer: ${valueText}.`,
            );
        }
        summary[field] = value;
    }

    const missingFields = tapSummaryFields.filter(
        (field) => !Object.hasOwn(summary, field),
    );
    if (missingFields.length > 0) {
        throw new Error(
            `Missing Node TAP summary fields: ${missingFields.join(", ")}.`,
        );
    }
    return summary;
}

export function enforceTapSummary(summary, expectedTests, label) {
    const violations = [];
    if (summary.tests !== expectedTests) {
        violations.push(
            `tests expected ${expectedTests} but observed ${summary.tests}`,
        );
    }
    if (summary.pass !== expectedTests) {
        violations.push(
            `pass expected ${expectedTests} but observed ${summary.pass}`,
        );
    }
    for (const field of ["fail", "cancelled", "skipped", "todo"]) {
        if (summary[field] !== 0) {
            violations.push(
                `${field} expected 0 but observed ${summary[field]}`,
            );
        }
    }
    if (violations.length > 0) {
        throw new Error(
            `${label} TAP summary rejected: ${violations.join("; ")}.`,
        );
    }
    return summary;
}

export function validateTapSummary(output, expectedTests, label) {
    return enforceTapSummary(parseTapSummary(output), expectedTests, label);
}

async function verifyHealth(origin) {
    const response = await fetch(`${origin}/.cratis/commands`, {
        signal: AbortSignal.timeout(startupTimeoutMs),
    });
    if (!response.ok) {
        throw new Error(
            `The Kotlin Spring Boot sample health endpoint returned HTTP ${response.status}.`,
        );
    }
}

async function runChild(origin, childRun) {
    report(`\n=== ${childRun.label} ===`);
    const runner = spawn(
        process.execPath,
        [
            "--import",
            "tsx",
            "--test",
            "--test-reporter=tap",
            "--test-timeout=30000",
            childRun.contract,
        ],
        {
            env: {
                ...process.env,
                ARC_KOTLIN_SAMPLE_ORIGIN: origin,
                TZ: childRun.timeZone,
            },
            stdio: ["inherit", "pipe", "pipe"],
        },
    );
    activeTestRunner = runner;

    let tapOutput = "";
    const onStdout = (chunk) => {
        const text = chunk.toString();
        process.stdout.write(text);
        tapOutput = `${tapOutput}${text}`.slice(-capturedTapOutputLimit);
    };
    const onStderr = (chunk) => process.stderr.write(chunk);
    runner.stdout.on("data", onStdout);
    runner.stderr.on("data", onStderr);

    const outcome = await new Promise((resolveOutcome) => {
        let settled = false;
        const finish = (result) => {
            if (settled) return;
            settled = true;
            runner.off("error", onError);
            runner.off("close", onClose);
            runner.stdout.off("data", onStdout);
            runner.stderr.off("data", onStderr);
            resolveOutcome(result);
        };
        const onError = (error) => finish({ error, exitCode: 1 });
        const onClose = (code, signal) =>
            finish({ exitCode: code ?? 1, signal });
        runner.once("error", onError);
        runner.once("close", onClose);
    });
    if (activeTestRunner === runner) activeTestRunner = undefined;

    let summary;
    let summaryError;
    try {
        summary = parseTapSummary(tapOutput);
        enforceTapSummary(summary, childRun.expectedTests, childRun.label);
    } catch (error) {
        summaryError = error;
    }

    if (outcome.error) {
        reportError(
            `Child runtime contract failed to start: ${childRun.label}: ${outcome.error}`,
        );
    }
    if (summaryError) reportError(summaryError);

    const passed = outcome.exitCode === 0 && !outcome.error && !summaryError;
    const failureDetail = outcome.signal
        ? `signal ${outcome.signal}`
        : `exit ${outcome.exitCode}`;
    report(
        `=== ${childRun.label}: ${passed ? "passed" : `failed (${failureDetail})`} ===`,
    );
    return { passed, summary };
}

async function run() {
    const jar = process.env.ARC_KOTLIN_SAMPLE_JAR;
    const java = process.env.ARC_KOTLIN_JAVA ?? "java";
    if (!jar) {
        throw new Error(
            "ARC_KOTLIN_SAMPLE_JAR must point to the executable Kotlin Spring Boot sample jar.",
        );
    }

    gateTimer = setTimeout(() => {
        timedOut = true;
        process.exitCode = 1;
        reportError(
            `Runtime proxy gate exceeded ${gateTimeoutMs / 1_000} seconds.`,
        );
        void stopEverything();
    }, gateTimeoutMs);

    application = spawn(
        java,
        ["-jar", jar, "--server.address=127.0.0.1", "--server.port=0"],
        {
            detached: process.platform !== "win32",
            env: { ...process.env, SPRING_MAIN_BANNER_MODE: "off" },
            stdio: ["ignore", "pipe", "pipe"],
        },
    );

    const port = await waitForPort(application);
    const origin = `http://127.0.0.1:${port}`;
    await verifyHealth(origin);

    const results = [];
    for (const childRun of childRuns) {
        if (interrupted || timedOut) break;
        results.push({ childRun, result: await runChild(origin, childRun) });
    }

    const allRunsCompleted = results.length === childRuns.length;
    const allRunsPassed =
        allRunsCompleted && results.every(({ result }) => result.passed);
    const observedPassedTests = results.reduce(
        (total, { result }) => total + (result.summary?.pass ?? 0),
        0,
    );
    report("\n=== runtime proxy gate summary ===");
    for (const { childRun, result } of results) {
        const observed = result.summary
            ? `${result.summary.tests} tests, ${result.summary.pass} passed, ${result.summary.fail} failed, ${result.summary.cancelled} cancelled, ${result.summary.skipped} skipped, ${result.summary.todo} todo`
            : "TAP summary unavailable";
        report(
            `${childRun.label}: ${result.passed ? "passed" : "failed"} (${observed})`,
        );
    }
    report(`Total observed passed tests: ${observedPassedTests}`);

    if (!allRunsPassed) process.exitCode = 1;
}

async function main() {
    const signalHandlers = new Map();
    for (const signal of ["SIGINT", "SIGTERM"]) {
        const handler = async () => {
            interrupted = true;
            process.exitCode = 1;
            await stopEverything();
        };
        signalHandlers.set(signal, handler);
        process.once(signal, handler);
    }

    try {
        await run();
    } catch (error) {
        reportError(error);
        process.exitCode = 1;
    } finally {
        await stopEverything();
        for (const [signal, handler] of signalHandlers) {
            process.off(signal, handler);
        }
    }
}

if (
    process.argv[1] &&
    resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
    await main();
}
