# SPDX-License-Identifier: AGPL-3.0-or-later
"""Production consumer/process protocol checks; no headset or effect claim."""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

SOURCE = Path(__file__).resolve().parents[1] / "recenter.py"
SPEC = importlib.util.spec_from_file_location("kiosk_recenter", SOURCE)
utility = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(utility)


def result(request, **overrides):
    value = {
        "schema": utility.RESULT_SCHEMA,
        "request_id": request["request_id"], "nonce": request["nonce"],
        "boot_id": request["boot_id"], "source_id": request["source"]["source_id"],
        "request_sha256": hashlib.sha256((json.dumps(request, sort_keys=True, separators=(",", ":")) + "\n").encode()).hexdigest(),
        "artifact": request["artifact"],
        "availability": {"status": "ready", "reason": "supported", "privilege": "android_shell_uid_2000"},
        "dispatch": {"state": "sent"}, "callback": {"state": "success"},
        "effect": {"state": "unverified"},
        "original": {"state": "retired", "retired": True}, "cleanup": {"state": "complete"},
    }
    value.update(overrides)
    return value


class ConsumerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kiosk-consumer-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.provider = self.root / "tools/shell_recenter/quest_shell_recenter.py"
        self.provider.parent.mkdir(parents=True)
        self.request = {
            "schema": "rusty.quest.shell_recenter.request.v1",
            "request_id": "a" * 32, "nonce": "b" * 32,
            "boot_id": "00000000-0000-4000-8000-000000000001",
            "source": {"revision": "c" * 40, "tree": "d" * 40, "source_id": "e" * 64},
            "artifact": {"path": str(self.root / "fixture.dex.jar"), "sha256": "f" * 64, "bytes": 1},
        }
        self.pin_path, self.profile_path = self.root / "pin.json", self.root / "profile.json"
        self.install_provider(result(self.request))

    def install_provider(self, response, code=0):
        # This producer speaks the real subprocess interface. It is explicitly
        # a process conformance fixture, never an AppFunction/headset simulator.
        source = "\n".join([
            "import json,pathlib,sys,time",
            "request=json.loads(pathlib.Path(sys.argv[3]).read_text(encoding='utf-8'))",
            "pathlib.Path(__file__).with_name('invocation.json').write_text(json.dumps({'command':sys.argv[1],'request':request,'argv':sys.argv[2:3]}))",
            "time.sleep(0.03)",
            f"print({json.dumps(json.dumps(response))})",
            f"sys.exit({code})",
        ])
        self.provider.write_text(source, encoding="utf-8")
        self.digest = hashlib.sha256(self.provider.read_bytes()).hexdigest()
        self.pin = {"revision": "c" * 40, "tree": "d" * 40, "provider": {
            "path": "tools/shell_recenter/quest_shell_recenter.py", "sha256": self.digest}}
        self.profile = {"schema": utility.PROFILE_SCHEMA,
                        "provider": {"path": str(self.provider), "sha256": self.digest},
                        "request": self.request}
        self.save()

    def save(self):
        self.profile_path.write_text(json.dumps(self.profile), encoding="utf-8")
        self.pin_path.write_text(json.dumps(self.pin), encoding="utf-8")

    def call(self, command="recenter"):
        return utility.invoke(command, self.profile_path, pin_path=self.pin_path)

    def invocation(self):
        return json.loads(self.provider.with_name("invocation.json").read_text())

    def test_real_subprocess_forwards_one_exact_request_and_named_operation(self):
        response, code = self.call()
        self.assertEqual(code, 0)
        self.assertEqual(response, result(self.request))
        self.assertEqual(self.invocation(), {"command": "recenter", "request": self.request, "argv": ["--request"]})

    def test_status_and_reconcile_are_only_passive_reconcile(self):
        for command in ("status", "reconcile"):
            self.call(command)
            self.assertEqual(self.invocation()["command"], "reconcile")

    def test_readiness_is_its_own_named_operation(self):
        self.call("readiness")
        self.assertEqual(self.invocation()["command"], "readiness")

    def test_unavailable_privilege_and_firmware_remain_explicit(self):
        for reason in ("shell_uid_required", "unsupported_firmware"):
            value = result(self.request, availability={"status": "unavailable", "reason": reason,
                           "privilege": "android_shell_uid_2000"}, dispatch={"state": "not_sent"},
                           callback={"state": "not_received"})
            self.install_provider(value, code=3)
            response, code = self.call("readiness")
            self.assertEqual(code, 3)
            self.assertIn(reason, utility.render(response))

    def test_late_error_and_original_error_payload_survive_producer_return(self):
        error = {"state": "error", "error": {"class": "FixtureError", "code": 7}}
        self.install_provider(result(self.request, callback=error), code=4)
        response, code = self.call()
        self.assertEqual(code, 4)
        self.assertEqual(response["callback"], error)

    def test_pending_original_is_held_and_does_not_become_success(self):
        self.install_provider(result(self.request, callback={"state": "unknown"},
                              original={"state": "active", "retired": False}, cleanup={"state": "held"}))
        response, _ = self.call("status")
        text = utility.render(response)
        self.assertIn("callback: unknown", text)
        self.assertIn("ownership: active; cleanup: held", text)
        self.assertNotIn("effect: success", text)

    def test_callback_is_never_presented_as_reference_frame_effect(self):
        response, _ = self.call()
        self.assertIn("Original callback: success", utility.render(response))
        self.assertIn("effect: unverified", utility.render(response))

    def test_receipt_from_another_original_is_rejected_after_single_invocation(self):
        for key in ("request_id", "nonce", "boot_id", "source_id"):
            self.install_provider(result(self.request, **{key: "wrong"}))
            with self.assertRaises(utility.UtilityError):
                self.call()
            self.assertEqual(self.invocation()["command"], "recenter")

    def test_complete_cleanup_cannot_hide_pending_original(self):
        self.install_provider(result(self.request, original={"state": "active", "retired": False}))
        with self.assertRaisesRegex(utility.UtilityError, "Cleanup cannot complete"):
            self.call()

    def test_result_must_bind_exact_request_hash_and_artifact(self):
        for field, value in (("request_sha256", "0" * 64), ("artifact", {"bytes": 2})):
            self.install_provider(result(self.request, **{field: value}))
            with self.assertRaisesRegex(utility.UtilityError, "exact request and artifact"):
                self.call()

    def test_expanded_effect_claim_is_rejected(self):
        self.install_provider(result(self.request, effect={"state": "verified"}))
        with self.assertRaises(utility.UtilityError):
            self.call()

    def test_changed_provider_and_source_revision_fail_before_process_launch(self):
        with patch.object(utility, "_wait_for_provider") as execute:
            self.provider.write_text("raise SystemExit('changed')", encoding="utf-8")
            with self.assertRaises(utility.UtilityError):
                self.call()
            execute.assert_not_called()
        self.install_provider(result(self.request))
        self.request["source"]["revision"] = "f" * 40
        self.save()
        with patch.object(utility, "_wait_for_provider") as execute:
            with self.assertRaises(utility.UtilityError):
                self.call()
            execute.assert_not_called()

    def test_unknown_command_and_profile_expansion_never_dispatch(self):
        with self.assertRaises(utility.UtilityError):
            self.call("shell")
        self.profile["command"] = "anything"
        self.save()
        with self.assertRaises(utility.UtilityError):
            self.call()

    def test_duplicate_json_keys_are_damage(self):
        self.profile_path.write_text('{"schema": "one", "schema": "two"}', encoding="utf-8")
        with self.assertRaisesRegex(utility.UtilityError, "Duplicate"):
            self.call()

    def test_host_interrupt_keeps_producer_and_reads_actual_final_eof(self):
        process = Mock()
        process.stdout = io.BytesIO(b'{"done":true}')
        process.stderr = io.BytesIO(b'')
        process.wait.side_effect = [KeyboardInterrupt(), 0]
        with patch.object(utility.subprocess, "Popen", return_value=process) as start:
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(utility._wait_for_provider(["fixture"]), (0, '{"done":true}', ""))
        process.kill.assert_not_called()
        process.terminate.assert_not_called()
        self.assertEqual(process.wait.call_count, 2)
        self.assertNotIn("timeout", start.call_args.kwargs)
        self.assertTrue(start.call_args.kwargs.get("start_new_session") or start.call_args.kwargs.get("creationflags"))
        self.assertTrue(process.stdout.closed)
        self.assertTrue(process.stderr.closed)

    def test_observer_exception_waits_for_original_without_termination(self):
        process = Mock()
        process.stdout.read.side_effect = OSError("fixture observer failure")
        process.stderr = io.BytesIO(b'')
        with patch.object(utility.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(utility.UtilityError, "after original reap"):
                utility._wait_for_provider(["fixture"])
        process.wait.assert_called_once()
        process.kill.assert_not_called()
        process.terminate.assert_not_called()

    def test_actual_malformed_utf8_and_large_stderr_drain_before_decode_error(self):
        script = self.root / "invalid-output.py"
        marker = self.root / "completed"
        script.write_text("import pathlib,sys\n"
                          "sys.stdout.buffer.write(b'\\xff')\n"
                          "sys.stdout.buffer.flush()\n"
                          "sys.stderr.buffer.write(b'x' * 2097152)\n"
                          "sys.stderr.buffer.flush()\n"
                          "pathlib.Path(sys.argv[1]).write_text('completed')\n", encoding="utf-8")
        with self.assertRaises(UnicodeDecodeError):
            utility._wait_for_provider([sys.executable, str(script), str(marker)])
        self.assertEqual(marker.read_text(), "completed")

    def test_actual_oversized_stdout_drains_through_real_process_completion(self):
        script = self.root / "large-output.py"
        marker = self.root / "completed"
        script.write_text("import pathlib,sys\n"
                          "sys.stdout.buffer.write(b'x' * 2097152)\n"
                          "sys.stdout.buffer.flush()\n"
                          "pathlib.Path(sys.argv[1]).write_text('completed')\n", encoding="utf-8")
        with self.assertRaisesRegex(utility.UtilityError, "after EOF/reap"):
            utility._wait_for_provider([sys.executable, str(script), str(marker)])
        self.assertEqual(marker.read_text(), "completed")

    def test_cli_help_explicitly_identifies_developer_privilege(self):
        output = subprocess.check_output([sys.executable, str(SOURCE), "--help"], text=True)
        self.assertIn("genuine shell UID 2000", output)
        self.assertIn("calibrated reference frame", output)
        self.assertIn("status", output)


if __name__ == "__main__":
    unittest.main()
