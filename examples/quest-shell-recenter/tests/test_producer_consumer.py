# SPDX-License-Identifier: AGPL-3.0-or-later
"""Optional actual Quest/Kiosk passive conformance. Never contacts a device."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

SPEC = importlib.util.spec_from_file_location("kiosk_recenter_pair", Path(__file__).resolve().parents[1] / "recenter.py")
utility = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(utility)
UPSTREAM_ROOT = os.environ.get("QUEST_SHELL_RECENTER_UPSTREAM_ROOT")


@unittest.skipUnless(UPSTREAM_ROOT, "Set QUEST_SHELL_RECENTER_UPSTREAM_ROOT for the actual passive producer pair")
class ProducerConsumerTests(unittest.TestCase):
    def test_actual_upstream_bytes_and_source_match_reviewed_dependency(self):
        root = Path(UPSTREAM_ROOT).resolve()
        pin = json.loads(utility.PIN_PATH.read_text(encoding="utf-8"))
        provider = root / pin["provider"]["path"]
        self.assertEqual(hashlib.sha256(provider.read_bytes()).hexdigest(), pin["provider"]["sha256"])
        for expression, expected in (("HEAD", pin["revision"]), ("HEAD^{tree}", pin["tree"])):
            actual = subprocess.check_output(["git", "-C", str(root), "rev-parse", expression], text=True).strip()
            self.assertEqual(actual, expected)

    def test_actual_quest_reconcile_preserves_pending_and_final_without_adb(self):
        provider = Path(UPSTREAM_ROOT).resolve() / "tools/shell_recenter/quest_shell_recenter.py"
        with tempfile.TemporaryDirectory(prefix="kiosk-quest-passive-") as temporary:
            root = Path(temporary).resolve()
            output = root / "original"
            output.mkdir()
            request = {
                "schema": "rusty.quest.shell_recenter.request.v1", "request_id": uuid.uuid4().hex, "nonce": uuid.uuid4().hex,
                "adb": str(root / "NEVER_EXECUTE_ADB"), "canonical_serial": "CONFORMANCE_ONLY",
                "selector": "CONFORMANCE_ONLY", "boot_id": "00000000-0000-4000-8000-000000000001",
                "operator_id": "passive-host-conformance",
                "artifact": {"path": str(root / "absent.dex.jar"), "sha256": "c" * 64, "bytes": 1},
                "source": {"revision": "d" * 40, "tree": "e" * 40, "source_id": "f" * 64},
                "firmware": {"fingerprint": "conformance/not-a-device", "vrshell_sha256": "1" * 64,
                             "framework_sha256": "2" * 64}, "output_dir": str(output),
            }
            digest = hashlib.sha256(provider.read_bytes()).hexdigest()
            pin = {"revision": request["source"]["revision"], "tree": request["source"]["tree"], "provider": {
                "path": "tools/shell_recenter/quest_shell_recenter.py", "sha256": digest}}
            profile = {"schema": utility.PROFILE_SCHEMA, "provider": {"path": str(provider), "sha256": digest},
                       "request": request}
            profile_path, pin_path = root / "profile.json", root / "pin.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            pin_path.write_text(json.dumps(pin), encoding="utf-8")
            request_hash = hashlib.sha256((json.dumps(request, sort_keys=True, separators=(",", ":")) + "\n").encode()).hexdigest()
            value = {
                "schema": utility.RESULT_SCHEMA, "request_id": request["request_id"], "nonce": request["nonce"],
                "boot_id": request["boot_id"], "source_id": request["source"]["source_id"],
                "request_sha256": request_hash, "artifact": request["artifact"],
                "availability": {"status": "ready", "reason": "passive-fixture", "privilege": "android_shell_uid_2000"},
                "dispatch": {"state": "sent"}, "callback": {"state": "unknown"}, "effect": {"state": "unverified"},
                "original": {"state": "active", "retired": False}, "cleanup": {"state": "held"},
            }
            (output / "STATUS.json").write_text(json.dumps(value), encoding="utf-8")
            actual, code = utility.invoke("status", profile_path, pin_path=pin_path)
            self.assertEqual(actual, value)
            self.assertEqual(code, 1)
            value.update(callback={"state": "error", "error": {"code": "late-fixture-error"}},
                         original={"state": "retired", "retired": True}, cleanup={"state": "complete"})
            (output / "RESULT.json").write_text(json.dumps(value), encoding="utf-8")
            actual, code = utility.invoke("reconcile", profile_path, pin_path=pin_path)
            self.assertEqual(actual, value)
            self.assertEqual(code, 1)  # The original callback error retains producer failure status.
            # Changed profiles cannot use an old original's same IDs as admission.
            profile["request"]["operator_id"] = "changed-fixture-operator"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            with self.assertRaises(utility.UtilityError):
                utility.invoke("status", profile_path, pin_path=pin_path)
            self.assertEqual(sorted(path.name for path in output.iterdir()), ["RESULT.json", "STATUS.json"])
