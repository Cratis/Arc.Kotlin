// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Globals } from '@cratis/arc';
import { FluentInputValidator, type FluentInput } from '../generated/runtime/FluentInput';
import { ValidateFluent, ValidateFluentValidator, type IValidateFluent } from '../generated/runtime/ValidateFluent';
import { CheckFluentValidator } from '../generated/runtime/CheckFluent';
import { CheckFluentBatch, CheckFluentBatchValidator, type CheckFluentBatchParameters } from '../generated/runtime/CheckFluentBatch';

type AssertTrue<T extends true> = T;
type SharedArrayEntriesAreNotNullable = AssertTrue<null extends NonNullable<CheckFluentBatchParameters['inputs']>[number] ? false : true>;

const origin = process.env.ARC_KOTLIN_SAMPLE_ORIGIN;
assert.ok(origin, 'a real running sample is required');
Globals.origin = origin;
Globals.apiBasePath = '';

function valid(): FluentInput {
    return { required: 'ok', nonempty: 'ok', minimum: 'ab', maximum: 'ab', range: 'ab', email: 'a@b.c', phone: '+47 123',
        url: 'HTTPS://a', pattern: 'ABC', greater: 3, atLeast: 2, less: 1, atMost: 2 };
}
interface Feedback { message: string; members: string[]; reason: string; severity: number }
interface Envelope { isSuccess: boolean; validationResults: Feedback[]; data?: { value: string } }
async function post(command: IValidateFluent): Promise<Envelope> {
    const response = await fetch(`${origin}/api/validate-fluent`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(command)});
    assert.ok(response.status === 200 || response.status === 400, `unexpected HTTP ${response.status}: ${await response.clone().text()}`);
    return await response.json() as Envelope;
}
function feedback(value: Feedback[]) { return value.map(({message, members, reason, severity}) => ({message, members, reason, severity})); }

test('all thirteen generated fluent rules agree with real JVM acceptance messages and paths', async () => {
    const cases: [keyof FluentInput, string | number | null][] = [
        ['required', null], ['required', ''], ['nonempty', ''], ['nonempty', ' \t\u00a0\ufeff\u2028'], ['nonempty', '\u0085'],
        ['minimum', 'a'], ['minimum', '😀'], ['minimum', null], ['maximum', '😀'], ['maximum', 'abc'], ['maximum', null],
        ['range', ''], ['range', 'abcd'], ['range', null], ['email', 'a@b.c\n'], ['email', 'a\u00a0@b.c'], ['email', 'a\u0085@b.c'], ['email', ''],
        ['phone', '١٢٣'], ['phone', '+47\u00a0'], ['phone', null], ['url', 'ftp://a'], ['url', 'http://\nabc'], ['url', 'http://a\n'], ['url', ''],
        ['pattern', 'ABC\n'], ['pattern', 'ABC'], ['pattern', ''], ['pattern', null],
        ['pattern', ']'], ['pattern', ']]'], ['pattern', ']a'], ['pattern', ']\n'],
        ['greater', 2], ['greater', 2.1], ['greater', null], ['atLeast', 1.9], ['atLeast', 2], ['atLeast', null],
        ['less', 2], ['less', 1.9], ['less', null], ['atMost', 2.1], ['atMost', 2], ['atMost', null],
        ['greater', Number.MAX_SAFE_INTEGER], ['greater', Number.MAX_SAFE_INTEGER + 1], ['greater', 5e-324]
    ];
    for (const [member, value] of cases) {
        const input = {...valid(), [member]: value};
        const command = {input, siblings: [], javaInput: null};
        const client = new ValidateFluentValidator().validate(command);
        const server = await post(command);
        assert.equal(server.isSuccess, client.length === 0, JSON.stringify({member, value, server}));
        assert.deepEqual(feedback(server.validationResults), feedback(client), JSON.stringify({member, value}));
        const direct = new FluentInputValidator().validate(input);
        assert.deepEqual(client.map(x => x.members), direct.map(x => x.members.length ? x.members.map(m => `input.${m}`) : ['input']));
    }
});

test('nested siblings and ordinary Java declarations execute without manual validator beans', async () => {
    const command = {input: {...valid(), minimum: 'x'}, siblings: [valid(), {...valid(), pattern: 'bad'}], javaInput: {name: ''}, group: {child: {...valid(), maximum: 'long'}}};
    const client = new ValidateFluentValidator().validate(command);
    assert.deepEqual(client.flatMap(x => x.members), ['group.child.maximum', 'input.minimum', 'javaInput.name', 'siblings[1].pattern']);
    const server = await post(command);
    assert.equal(server.isSuccess, false);
    assert.deepEqual(feedback(server.validationResults), feedback(client));
    const accepted = await post({input: valid(), siblings: [valid()], javaInput: {name: 'ok'}});
    assert.equal(accepted.isSuccess, true);
});

test('omitted query default supplied null and supplied model retain distinct matching behavior', async () => {
    for (const [arguments_, expected] of [[{}, 'default'], [{input: null}, 'null'], [{input: {name: 'ok'}}, 'ok'], [{input: {name: ''}}, null]] as const) {
        const client = new CheckFluentValidator().validate(arguments_);
        const response = await fetch(`${origin}/api/fluent`, {method: 'QUERY', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({arguments: arguments_})});
        const server = await response.json() as Envelope;
        assert.equal(server.isSuccess, expected !== null, JSON.stringify(server));
        assert.deepEqual(feedback(server.validationResults), feedback(client));
        if (expected !== null) assert.equal(server.data?.value, expected);
    }
    // Ordinary strict TypeScript calls: null belongs to the container, not its entries.
    const query = new CheckFluentBatch();
    for (const arguments_ of [{}, {inputs: null}, {inputs: []}, {inputs: [{name: ' a '}]}]) {
        assert.equal(new CheckFluentBatchValidator().validate(arguments_).length, 0);
        const result = await query.perform(arguments_);
        assert.equal(result.isSuccess, true, JSON.stringify(result));
        assert.equal(result.data.value, arguments_.inputs == null ? ('inputs' in arguments_ ? 'null' : 'omitted') : String(arguments_.inputs.length));
    }
    const alias = {name: ''};
    assert.deepEqual(new CheckFluentBatchValidator().validate({inputs: [alias, alias]}).flatMap(x => x.members), ['inputs[0].name']);
    const batch = {inputs: [{name: ''}, {name: ''}]};
    const response = await fetch(`${origin}/api/fluent-batch`, {method: 'QUERY', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({arguments: batch})});
    const server = await response.json() as Envelope;
    const client = new CheckFluentBatchValidator().validate(batch);
    assert.equal(server.isSuccess, false);
    assert.deepEqual(client.flatMap(x => x.members), ['inputs[0].name', 'inputs[1].name']);
    assert.deepEqual(feedback(server.validationResults), feedback(client));
});

test('generated command executes accepted input and rejects invalid input before transport', async () => {
    const command = new ValidateFluent();
    command.input = valid(); command.siblings = []; command.javaInput = {name: 'ok'};
    assert.equal((await command.execute()).isSuccess, true);
    command.input = {...valid(), pattern: 'bad'};
    const original = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = ((...args: Parameters<typeof fetch>) => { calls++; return original(...args); }) as typeof fetch;
    try {
        const result = await command.execute();
        assert.equal(result.isSuccess, false);
        assert.deepEqual(result.validationResults.flatMap(x => x.members), ['input.pattern']);
        assert.equal(calls, 0);
    } finally { globalThis.fetch = original; }
});
