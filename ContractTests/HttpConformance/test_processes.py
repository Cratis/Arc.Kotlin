# Copyright (c) Cratis. All rights reserved.
# Licensed under the MIT license. See LICENSE file in the project root for full license information.

import json
import os
import io
import signal
import time
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import run


class Child:
    def __init__(self, stubborn=False):
        self.returncode = None
        self.terminated = False
        self.killed = False
        self.waited = False
        self.stubborn = stubborn
        self.stdout = io.BytesIO()
    def poll(self):
        return self.returncode
    def terminate(self):
        self.terminated = True
        if not self.stubborn:
            self.returncode = 0
    def kill(self):
        self.killed = True
        self.returncode = -9
    def wait(self, timeout):
        if self.returncode is None:
            raise subprocess.TimeoutExpired("test child", timeout)
        self.waited = True
        return self.returncode


class ProcessTest(unittest.TestCase):
    def setUp(self):
        output = os.environ.get("AI_WORK_OUTPUT")
        if not output:
            self.fail("Run through lifecycle AI_WORK_OUTPUT")
        self.temp = tempfile.TemporaryDirectory(dir=output)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.log = self.root / "host.log"

    def spawn(self, child, text):
        def start(*args, **kwargs):
            child.stdout = io.BytesIO(text.encode())
            return child
        return patch.object(run.subprocess, "Popen", side_effect=start)

    def test_ready_child_is_reaped_on_success_and_assertion_failure(self):
        for fail in (False, True):
            child = Child()
            with self.subTest(fail=fail), self.spawn(child, "Tomcat started on port 12345 (http)\n"):
                try:
                    with run.server(["test-java"], self.root, self.log) as origin:
                        self.assertEqual("http://127.0.0.1:12345", origin)
                        if fail:
                            raise AssertionError("test failure")
                except AssertionError:
                    if not fail:
                        raise
                self.assertTrue(child.terminated)
                self.assertTrue(child.waited)

    def test_startup_timeout_reaps_child_and_escalates_only_when_needed(self):
        child = Child(stubborn=True)
        with self.spawn(child, "starting\n"):
            with self.assertRaises(TimeoutError):
                with run.server(["test-java"], self.root, self.log, startup_timeout=0):
                    self.fail("Must not yield before startup")
        self.assertTrue(child.terminated)
        self.assertTrue(child.killed)
        self.assertTrue(child.waited)

    def test_entire_exchange_phase_has_a_deadline(self):
        child = Child(stubborn=True)
        with self.spawn(child, "Tomcat started on port 12345 (http)\n"):
            with self.assertRaises(TimeoutError):
                with run.server(["test-java"], self.root, self.log, total_timeout=0.08):
                    time.sleep(0.15)
        self.assertTrue(child.killed)
        self.assertTrue(child.waited)

    def test_logs_are_bounded_and_violation_reaps_child(self):
        child = Child()
        with self.spawn(child, "x" * 1000), self.assertRaises(RuntimeError):
            with run.server(["test-java"], self.root, self.log, max_log_bytes=100):
                self.fail("Oversized startup output must fail")
        self.assertEqual(100, self.log.stat().st_size)
        self.assertTrue(child.terminated)
        self.assertTrue(child.waited)

    def test_sigterm_unwinds_and_joins_owned_child(self):
        child = Child()
        original = signal.getsignal(signal.SIGTERM)
        with self.spawn(child, "Tomcat started on port 12345 (http)\n"), self.assertRaises(InterruptedError):
            with run.termination_guard():
                with run.server(["test-java"], self.root, self.log):
                    signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
        self.assertEqual(original, signal.getsignal(signal.SIGTERM))
        self.assertTrue(child.terminated)
        self.assertTrue(child.waited)

    def test_jvm_environment_drops_runtime_and_spring_injection(self):
        with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "-javaagent:evil", "JDK_JAVA_OPTIONS": "-Dunsafe=1",
                                    "SPRING_APPLICATION_JSON": "{}", "SPRING_CONFIG_LOCATION": "http://other-host/config"}):
            env = run.java_environment(self.root)
        self.assertEqual("UTC", env["TZ"])
        self.assertTrue(set(env).isdisjoint({"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "SPRING_APPLICATION_JSON", "SPRING_CONFIG_LOCATION"}))

    def test_spawn_failure_propagates_without_invented_child(self):
        with patch.object(run.subprocess, "Popen", side_effect=OSError("spawn failure")):
            with self.assertRaises(OSError):
                with run.server(["missing"], self.root, self.log):
                    self.fail("Missing process cannot be ready")

    def test_dotnet_wrong_runtime_and_duplicate_readiness_fail_and_reap(self):
        ready = {"kind": "http-conformance-ready", "baseUrl": "http://127.0.0.1:12345", "runtime": "10.0.11",
                 "coreRuntimeDirectory": "/dotnet/shared/Microsoft.NETCore.App/10.0.11/",
                 "aspNetCoreAssembly": "/dotnet/shared/Microsoft.AspNetCore.App/10.0.11/Microsoft.AspNetCore.dll"}
        text = json.dumps(ready, separators=(",", ":")) + "\n"
        for bad in (text.replace('"runtime":"10.0.11"', '"runtime":"10.0.12"'), text + text):
            child = Child()
            with self.subTest(text=bad), self.spawn(child, bad), self.assertRaises(RuntimeError):
                with run.server(["test-dotnet"], self.root, self.log, dotnet=True, runtime="10.0.11"):
                    self.fail("Invalid runtime cannot pass")
            self.assertTrue(child.terminated)
            self.assertTrue(child.waited)


if __name__ == "__main__":
    unittest.main()
