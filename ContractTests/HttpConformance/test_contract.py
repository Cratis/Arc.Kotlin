# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

import copy
import json
import unittest
from unittest.mock import patch

import contract

ID = "11111111-1111-1111-1111-111111111111"
ID2 = "22222222-2222-2222-2222-222222222222"


class Response:
    def __init__(self, status, body):
        self.status = status
        self.headers = {"Content-Type": "application/json", "Cache-Control": "no-store"}
        self.content = json.dumps(body).encode()
    def read(self, limit):
        return self.content[:limit]
    def __enter__(self):
        return self
    def __exit__(self, *args):
        return False


def exchanges():
    def ok(**fields):
        return {"isSuccess": True, "isAuthorized": True, "isValid": True, "hasExceptions": False,
                "correlationId": ID, "validationResults": [], "exceptionMessages": [], **fields}
    row = {"id": ID, "title": "HTTP parity task", "completed": False}
    other = {"id": ID2, "title": "Distinct second task", "completed": False}
    done = dict(row, completed=True)
    return [(200, ok(data=[])), (200, ok(response={"id": ID, "title": row["title"]})),
            (200, ok(response={"id": ID2, "title": other["title"]})),
            (200, ok(data=row)), (200, ok(data=other)), (200, ok(data=other)), (200, ok(data=row)),
            (200, ok(data=[row, other])), (200, ok()), (200, ok(data=[row, other])),
            (200, ok(response=done)), (200, ok(data=[done, other])),
            (400, {"isSuccess": False, "isValid": False, "hasExceptions": False, "exceptionMessages": [],
                   "exceptionStackTrace": "", "validationResults": [{"reason": "malformedRequest"}]}),
            (200, ok(data=[done, other])), (404, {})]


class ContractTest(unittest.TestCase):
    def run_contract(self, replies):
        recorded = []
        requests = []
        def send(request, timeout):
            requests.append((request.method, request.full_url, request.data))
            return Response(*replies[len(requests) - 1])
        with patch.object(contract, "urlopen", side_effect=send):
            result = contract.exercise("http://127.0.0.1:12345", recorded)
        return result, recorded, requests

    def test_shared_success_contract_is_exact_and_keeps_raw_bodies(self):
        cases, evidence, requests = self.run_contract(exchanges())
        self.assertEqual(9, len(cases))
        self.assertEqual(15, len(evidence))
        self.assertEqual(3, sum(method == "QUERY" for method, _, _ in requests))
        for entry in evidence:
            self.assertEqual(entry["json"], json.loads(entry["body"]))
        self.assertEqual({"taskId": ID}, json.loads(requests[10][2]))

    def test_boolean_flags_are_not_interchangeable_with_integers(self):
        replies = exchanges()
        replies[1][1]["isSuccess"] = 1
        with self.assertRaises(AssertionError):
            self.run_contract(replies)

    def test_status_field_type_state_and_execution_mutations_fail(self):
        mutations = [
            (1, "status", 201), (1, "response", {"id": ID, "title": "wrong"}),
            (3, "data", {"id": ID, "title": "HTTP parity task", "completed": True}),
            (4, "data", {"id": ID, "title": "HTTP parity task", "completed": False}),
            (5, "data", {"id": ID, "title": "HTTP parity task", "completed": False}),
            (6, "data", None), (7, "data", []), (9, "data", {"one": {}, "two": {}}),
            (9, "data", [{"id": ID, "title": "replaced", "completed": False}, {"id": ID2}]),
            (10, "response", {"id": ID, "title": "HTTP parity task", "completed": False}),
            (12, "status", 200), (13, "data", [{"id": ID}, {"id": ID2}]), (14, "status", 200),
        ]
        for index, field, value in mutations:
            replies = copy.deepcopy(exchanges())
            if field == "status":
                replies[index] = value, replies[index][1]
            else:
                replies[index][1][field] = value
            with self.subTest(index=index, field=field), self.assertRaises((AssertionError, TypeError)):
                self.run_contract(replies)

    def test_http_opener_ignores_proxies_and_refuses_redirects(self):
        from urllib.request import Request, ProxyHandler
        request = Request("http://127.0.0.1:12345/api/tasks")
        with patch.object(contract, "build_opener") as factory:
            contract.urlopen(request, 1)
        proxy, redirect = factory.call_args.args
        self.assertIsInstance(proxy, ProxyHandler)
        self.assertEqual({}, proxy.proxies)
        self.assertIsNone(redirect.redirect_request(request, None, 302, "Found", {}, "http://other-host/"))
        factory.return_value.open.assert_called_once_with(request, timeout=1)

    def test_non_loopback_origins_are_refused_before_network_access(self):
        for origin in ("https://127.0.0.1:1", "http://example.com:1", "http://127.0.0.1", "http://user@127.0.0.1:1",
                       "http://127.0.0.1:1/path", "http://127.0.0.1:1?query"):
            with self.subTest(origin=origin), patch.object(contract, "urlopen") as send:
                with self.assertRaises(ValueError):
                    contract.exercise(origin, [])
                send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
