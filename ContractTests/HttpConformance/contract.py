# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.
"""Selected HTTP task-board contracts, independently asserted for each real host.

Identifiers are checked by relationship, not normalized into an invented byte-equality claim.
Extra envelope fields and framework error text remain in the raw evidence but are not equalized.
"""

import json
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler
from urllib.parse import quote, urlparse
from uuid import UUID


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def urlopen(request, timeout):
    return build_opener(ProxyHandler({}), NoRedirect()).open(request, timeout=timeout)


def expect_equal(expected, actual, label):
    if json.dumps(expected, sort_keys=True) != json.dumps(actual, sort_keys=True):
        raise AssertionError(f"{label}: expected {expected!r}, received {actual!r}")


def success(exchange):
    expect_equal(200, exchange["status"], "HTTP success")
    body = exchange["json"]
    for name, expected in (("isSuccess", True), ("isAuthorized", True), ("isValid", True), ("hasExceptions", False)):
        expect_equal(expected, body[name], name)
    expect_equal([], body["validationResults"], "validationResults")
    expect_equal([], body["exceptionMessages"], "exceptionMessages")
    UUID(body["correlationId"])
    if exchange["method"] == "QUERY":
        expect_equal("no-store", exchange["cacheControl"], "QUERY cache policy")
    return body


def exercise(origin, exchanges):
    parsed = urlparse(origin)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port or parsed.path not in ("", "/"):
        raise ValueError("Conformance host must be an explicit loopback HTTP origin")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Unexpected origin components")
    cases = []

    def send(name, method, path, payload=None, raw=None):
        data = raw if raw is not None else None if payload is None else json.dumps(payload).encode()
        request = Request(origin + path, data=data, method=method, headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            response = urlopen(request, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            content = response.read(1_000_001)
            if len(content) > 1_000_000:
                raise ValueError("Oversized conformance response")
            text = content.decode("utf-8")
            try:
                body = json.loads(text)
            except ValueError:
                body = None
            result = {"name": name, "method": method, "path": path, "request": payload,
                      "rawRequest": raw.decode() if raw is not None else None,
                      "status": response.status, "body": text, "json": body,
                      "contentType": response.headers.get("Content-Type"), "cacheControl": response.headers.get("Cache-Control")}
            exchanges.append(result)
            return result

    def snapshot(name, method="GET", expected=None):
        body = success(send(name, method, "/api/tasks", {"arguments": {}} if method == "QUERY" else None))
        rows = body["data"]
        if not isinstance(rows, list) or any(not isinstance(value, dict) for value in rows):
            raise AssertionError("Expected query data to be an array of task objects")
        expect_equal(sorted(expected or [], key=lambda value: value['id']), sorted(rows, key=lambda value: value['id']), "Complete task snapshot")

    snapshot("initial-list", expected=[])
    cases.append("initial-empty-query")

    tasks = []
    for index, title in enumerate(("HTTP parity task", "Distinct second task")):
        body = success(send(f"create-{index}", "POST", "/api/create-task", {"title": title}))
        identifier = body["response"]["id"]
        UUID(identifier)
        expect_equal({"id": identifier, "title": title}, body["response"], "Typed command response")
        tasks.append({"id": identifier, "title": title, "completed": False})
    if tasks[0]['id'] == tasks[1]['id']:
        raise AssertionError("Distinct creates must yield different identities")
    cases.append("typed-command-response")

    for index, task in enumerate(tasks):
        body = success(send(f"read-get-{index}", "GET", "/api/tasks/by-id?id=" + quote(task['id'])))
        expect_equal(task, body["data"], "GET identifier selects requested task")
    cases.append("get-query-arguments")
    for index, task in enumerate(reversed(tasks)):
        body = success(send(f"read-query-{index}", "QUERY", "/api/tasks/by-id", {"arguments": {"id": task['id']}}))
        expect_equal(task, body["data"], "QUERY identifier selects requested task")
    cases.append("structured-query-arguments")

    snapshot("list-query", "QUERY", tasks)
    cases.append("enumerable-query-envelope")
    validation = success(send("validate", "POST", "/api/create-task/validate", {"title": "Validation does not execute"}))
    if validation.get("response") is not None:
        raise AssertionError("Validation must not produce a handler response")
    snapshot("list-after-validate", expected=tasks)
    cases.append("validate-without-side-effects")

    tasks[0] = dict(tasks[0], completed=True)
    body = success(send("complete", "POST", "/api/complete-task", {"taskId": tasks[0]['id']}))
    expect_equal(tasks[0], body["response"], "Typed completion response")
    snapshot("list-after-completed", expected=tasks)
    cases.append("command-response-and-persisted-state")

    malformed = send("malformed-json", "POST", "/api/create-task", raw=b'{"title":')
    expect_equal(400, malformed["status"], "Malformed JSON status")
    rejected = malformed["json"]
    expect_equal(False, rejected["isSuccess"], "Malformed result")
    expect_equal(False, rejected["isValid"], "Malformed validation")
    expect_equal(False, rejected["hasExceptions"], "Malformed command is validation, not an exception")
    expect_equal([], rejected["exceptionMessages"], "No parser exception details")
    expect_equal("", rejected["exceptionStackTrace"], "No parser stack trace")
    expect_equal(["malformedRequest"], [v["reason"] for v in rejected["validationResults"]], "Malformed classification")
    snapshot("list-after-malformed", expected=tasks)
    cases.append("malformed-input-no-side-effects")

    missing = send("unknown-route", "GET", "/api/nonexistent-conformance-route")
    expect_equal(404, missing["status"], "Unknown route")
    cases.append("unknown-route-status")
    return cases
