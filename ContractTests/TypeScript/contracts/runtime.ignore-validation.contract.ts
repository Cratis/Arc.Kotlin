// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Globals } from '@cratis/arc';
import { IgnoredInputValidator, type IgnoredInput } from '../generated/runtime/IgnoredInput';
import { JavaIgnoredInputValidator, type JavaIgnoredInput } from '../generated/runtime/JavaIgnoredInput';
import { JavaFluentInputValidator } from '../generated/runtime/JavaFluentInput';
import { ValidateIgnored, ValidateIgnoredValidator } from '../generated/runtime/ValidateIgnored';
import { ValidateJavaIgnored, ValidateJavaIgnoredValidator } from '../generated/runtime/ValidateJavaIgnored';
import { CheckIgnored, CheckIgnoredValidator } from '../generated/runtime/CheckIgnored';
import { CheckJavaIgnored, CheckJavaIgnoredValidator } from '../generated/runtime/CheckJavaIgnored';

const origin = process.env.ARC_KOTLIN_SAMPLE_ORIGIN;
assert.ok(origin, 'a real running sample is required');
Globals.origin = origin;
Globals.apiBasePath = '';

function kotlinInput(): IgnoredInput {
    return { ignoredText: '', ignoredChild: {name: ''}, ignoredList: [{name: ''}], ignoredArray: [{name: ''}],
        ignoredMap: {wire: ''}, validated: {name: 'ok'}, sibling: 'ok' };
}
function javaInput(): JavaIgnoredInput {
    return { ignoredText: '', ignoredChild: {name: ''}, ignoredList: [], validated: {name: 'ok'}, sibling: 'ok' };
}
interface Feedback { message: string; members: string[]; reason: string; severity: number }
interface Envelope { isSuccess: boolean; validationResults: Feedback[]; data?: {value: string} }
function feedback(values: Feedback[]) { return values.map(({message, members, reason, severity}) => ({message, members, reason, severity})); }
async function send(path: string, method: string, body: unknown): Promise<Envelope> {
    const response = await fetch(`${origin}${path}`, {method, headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    assert.ok(response.status === 200 || response.status === 400, `unexpected HTTP ${response.status}: ${await response.clone().text()}`);
    return await response.json() as Envelope;
}

// Deliberately hostile getter values are validation-only probes, not JSON serialization fixtures.
test('generated direct Kotlin and Java model command and QUERY validators never read ignored getters', () => {
    let reads = 0;
    const kotlin = kotlinInput();
    for (const name of ['ignoredText', 'ignoredChild', 'ignoredList', 'ignoredArray', 'ignoredMap', 'ignoredNext']) {
        Object.defineProperty(kotlin, name, {enumerable: true, get() { reads++; throw new Error(`read ${name}`); }});
    }
    const java = javaInput();
    for (const name of ['ignoredText', 'ignoredChild', 'ignoredList']) {
        Object.defineProperty(java, name, {enumerable: true, get() { reads++; throw new Error(`read ${name}`); }});
    }
    assert.deepEqual(new IgnoredInputValidator().validate(kotlin), []);
    assert.deepEqual(new ValidateIgnoredValidator().validate({input: kotlin}), []);
    assert.deepEqual(new CheckIgnoredValidator().validate({input: kotlin}), []);
    assert.deepEqual(new JavaIgnoredInputValidator().validate(java), []);
    assert.deepEqual(new ValidateJavaIgnoredValidator().validate({input: java}), []);
    assert.deepEqual(new CheckJavaIgnoredValidator().validate({input: java}), []);
    kotlin.sibling = ''; java.sibling = '';
    assert.deepEqual(new IgnoredInputValidator().validate(kotlin).flatMap(v => v.members), ['sibling']);
    assert.deepEqual(new JavaIgnoredInputValidator().validate(java).flatMap(v => v.members), ['sibling']);
    assert.equal(reads, 0);
});

test('ignored containers and cycle are cut before iteration while an active alias and separate child root validate', () => {
    let reads = 0;
    const input = kotlinInput();
    input.ignoredList = new Proxy([{name: ''}], {get() { reads++; throw new Error('ignored list read'); }});
    input.ignoredArray = new Proxy([{name: ''}], {get() { reads++; throw new Error('ignored array read'); }});
    input.ignoredMap = new Proxy({key: ''}, {get() { reads++; throw new Error('ignored map read'); }, ownKeys() { reads++; throw new Error('ignored map iteration'); }});
    input.ignoredNext = input;
    const alias = {name: ''};
    input.ignoredChild = alias; input.validated = alias;
    assert.deepEqual(new IgnoredInputValidator().validate(input).flatMap(v => v.members), ['validated.name']);
    assert.deepEqual(new ValidateIgnoredValidator().validate({input}).flatMap(v => v.members), ['input.validated.name']);
    assert.deepEqual(new CheckIgnoredValidator().validate({input}).flatMap(v => v.members), ['input.validated.name']);
    assert.deepEqual(new JavaFluentInputValidator().validate(alias).flatMap(v => v.members), ['name']);
    assert.equal(reads, 0);
});

test('generated Kotlin and ordinary Java POST preflight and QUERY agree with actual JVM validation', async () => {
    for (const language of ['kotlin', 'java'] as const) {
        for (const invalid of [false, true]) {
            const input = language === 'kotlin' ? kotlinInput() : javaInput();
            // Native JSON preserves explicit null. The pinned command serializer cannot serialize
            // null nested models; generated transport cases below use the omitted default instead.
            if (language === 'kotlin') (input as IgnoredInput).ignoredNext = null;
            if (invalid) { const alias = {name: ''}; input.sibling = ''; input.ignoredChild = alias; input.validated = alias; }
            const client = language === 'kotlin'
                ? new ValidateIgnoredValidator().validate({input: input as IgnoredInput})
                : new ValidateJavaIgnoredValidator().validate({input: input as JavaIgnoredInput});
            const route = language === 'kotlin' ? '/api/validate-ignored' : '/api/validate-java-ignored';
            for (const suffix of ['', '/validate']) {
                const server = await send(route + suffix, 'POST', {input});
                assert.equal(server.isSuccess, !invalid, JSON.stringify(server));
                assert.deepEqual(feedback(server.validationResults), feedback(client));
            }
            const query = await send(language === 'kotlin' ? '/api/ignored' : '/api/java-ignored', 'QUERY', {arguments: {input}});
            assert.equal(query.isSuccess, !invalid, JSON.stringify(query));
            assert.deepEqual(feedback(query.validationResults), feedback(client));
            if (!invalid) assert.equal(query.data?.value, '', 'ignoredText still binds and is available to the handler');
        }
    }
});

test('generated transports retain serialized ignored values and required model binding', async () => {
    const kotlin = new ValidateIgnored(); kotlin.input = kotlinInput();
    const java = new ValidateJavaIgnored(); java.input = javaInput();
    const kotlinResult = await kotlin.execute();
    assert.equal(kotlinResult.isSuccess, true, JSON.stringify(kotlinResult));
    const javaResult = await java.execute();
    assert.equal(javaResult.isSuccess, true, JSON.stringify(javaResult));
    assert.equal((await new CheckIgnored().perform({input: kotlinInput()})).data.value, '');
    assert.equal((await new CheckJavaIgnored().perform({input: javaInput()})).data.value, '');
    assert.deepEqual(new CheckIgnored().requiredRequestParameters, ['input']);
    assert.deepEqual(new CheckJavaIgnored().requiredRequestParameters, ['input']);
    for (const body of [{}, {input: null}]) {
        assert.equal((await send('/api/validate-ignored', 'POST', body)).isSuccess, false);
    }
    for (const arguments_ of [{}, {input: null}]) {
        assert.equal((await send('/api/ignored', 'QUERY', {arguments: arguments_})).isSuccess, false);
    }
    const original = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = ((...args: Parameters<typeof fetch>) => { calls++; return original(...args); }) as typeof fetch;
    try {
        kotlin.input = {...kotlinInput(), sibling: ''};
        java.input = {...javaInput(), sibling: ''};
        assert.equal((await kotlin.execute()).isSuccess, false);
        assert.equal((await java.execute()).isSuccess, false);
        assert.equal(calls, 0);
    } finally { globalThis.fetch = original; }
});
