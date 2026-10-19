#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Developer-only Kiosk consumer of the pinned Rusty Quest shell provider."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import threading
from typing import Any

PROFILE_SCHEMA = "rusty.kiosk.shell_recenter.profile.v1"
RESULT_SCHEMA = "rusty.quest.shell_recenter.result.v1"
PIN_PATH = Path(__file__).with_name("UPSTREAM.json")
COMMANDS = {"readiness": "readiness", "recenter": "recenter", "status": "reconcile", "reconcile": "reconcile"}
MAX_JSON_BYTES = 1024 * 1024


class UtilityError(Exception):
    """No admission or effect success can be inferred from this failure."""


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise UtilityError(f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def _decode(data: str) -> dict[str, Any]:
    if len(data.encode("utf-8")) > MAX_JSON_BYTES:
        raise UtilityError("JSON exceeds the bounded utility document size")
    try:
        value = json.loads(data, object_pairs_hook=_object_pairs)
    except (ValueError, TypeError) as error:
        raise UtilityError("Expected one structured JSON object") from error
    if not isinstance(value, dict):
        raise UtilityError("Expected one structured JSON object")
    return value


def _read(path: Path) -> dict[str, Any]:
    if path.stat().st_size > MAX_JSON_BYTES:
        raise UtilityError("JSON exceeds the bounded utility document size")
    return _decode(path.read_text(encoding="utf-8-sig"))


def _shape(value: Any, keys: set[str], name: str) -> None:
    if not isinstance(value, dict) or set(value) != keys:
        raise UtilityError(f"Invalid {name} fields")


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _absolute_file(value: Any) -> Path:
    if not isinstance(value, str) or not Path(value).is_absolute():
        raise UtilityError("Provider must be an explicit absolute path")
    path = Path(value)
    if not path.is_file() or path.is_symlink():
        raise UtilityError("Provider must be a regular source file")
    return path.resolve(strict=True)


def load_profile(path: Path, pin_path: Path = PIN_PATH) -> tuple[Path, dict[str, Any], str]:
    profile, pin = _read(path), _read(pin_path)
    _shape(profile, {"schema", "provider", "request"}, "profile")
    _shape(profile["provider"], {"path", "sha256"}, "provider")
    if profile["schema"] != PROFILE_SCHEMA:
        raise UtilityError("Unsupported Kiosk developer profile schema")
    provider = _absolute_file(profile["provider"]["path"])
    expected = pin["provider"]["sha256"]
    if (not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected)
            or profile["provider"]["sha256"] != expected or _sha(provider) != expected):
        raise UtilityError("Quest provider bytes do not match the reviewed upstream pin")
    suffix = Path(pin["provider"]["path"]).parts
    if tuple(provider.parts[-len(suffix):]) != suffix:
        raise UtilityError("Provider path is outside the fixed Quest provider layout")
    request = profile["request"]
    # Quest owns detailed request validation, target admission and live firmware checks.
    # Kiosk binds its receipt to the selected original and exact reviewed source.
    if not isinstance(request, dict) or request.get("schema") != "rusty.quest.shell_recenter.request.v1":
        raise UtilityError("Expected the typed Quest shell recenter request")
    if (not isinstance(request.get("source"), dict) or request["source"].get("revision") != pin["revision"]
            or request["source"].get("tree") != pin["tree"]):
        raise UtilityError("Quest request revision differs from the reviewed dependency")
    for key in ("request_id", "nonce", "boot_id"):
        if not isinstance(request.get(key), str) or not request[key]:
            raise UtilityError(f"Missing request identity: {key}")
    if not isinstance(request["source"].get("source_id"), str):
        raise UtilityError("Missing Quest source identity")
    return provider, request, expected


def _wait_for_provider(argv: list[str]) -> tuple[int, str, str]:
    """Keep the producer alive through its genuine final, output EOF and reap.

    subprocess.run kills its child on KeyboardInterrupt. A separate process
    group plus independent binary readers avoids turning a host observation
    interruption into cancellation of an already-dispatched shell original.
    """
    options: dict[str, Any] = {"stdout": subprocess.PIPE, "stderr": subprocess.PIPE}
    if os.name == "nt":
        options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        options["start_new_session"] = True
    process = subprocess.Popen(argv, **options)
    captures: dict[str, bytearray] = {"stdout": bytearray(), "stderr": bytearray()}
    errors: list[BaseException] = []
    overflow = threading.Event()

    def drain(name: str) -> None:
        stream = getattr(process, name)
        try:
            while True:
                data = stream.read(65536)
                if not data:
                    break
                # Keep draining oversized/invalid output. Decode only after EOF.
                limit = MAX_JSON_BYTES if name == "stdout" else 65536
                available = max(0, limit - len(captures[name]))
                captures[name].extend(data[:available])
                if name == "stdout" and len(data) > available:
                    overflow.set()
        except BaseException as error:
            errors.append(error)

    readers = [threading.Thread(target=drain, args=(name,), name="KioskRecenter-" + name)
               for name in ("stdout", "stderr")]
    for reader in readers:
        reader.start()
    try:
        while True:
            try:
                code = process.wait()
                break
            except KeyboardInterrupt:
                print("Original still owned; waiting for its real completion. Status never retriggers it.",
                      file=sys.stderr, flush=True)
        for reader in readers:
            while True:
                try:
                    reader.join()
                    break
                except KeyboardInterrupt:
                    continue
        if errors:
            raise UtilityError("Provider output observation failed after original reap") from errors[0]
        if overflow.is_set():
            raise UtilityError("Provider result exceeded the bounded document size after EOF/reap")
        return code, bytes(captures["stdout"]).decode("utf-8"), bytes(captures["stderr"]).decode("utf-8", "replace")
    finally:
        # A host-side wait/formatting failure cannot close a still-producing
        # pipe. Independent readers retain it until actual output EOF.
        for reader in readers:
            while True:
                try:
                    reader.join()
                    break
                except KeyboardInterrupt:
                    continue
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                stream.close()


def validate_result(result: dict[str, Any], request: dict[str, Any]) -> None:
    if result.get("schema") != RESULT_SCHEMA:
        raise UtilityError("Unknown Quest result schema; retain original evidence and inspect status")
    for key in ("request_id", "nonce", "boot_id"):
        if result.get(key) != request[key]:
            raise UtilityError(f"Provider result does not belong to this original: {key}")
    if result.get("source_id") != request["source"]["source_id"]:
        raise UtilityError("Provider result source does not match the admitted request")
    request_hash = hashlib.sha256((json.dumps(request, sort_keys=True, separators=(",", ":")) + "\n").encode()).hexdigest()
    if result.get("request_sha256") != request_hash or result.get("artifact") != request.get("artifact"):
        raise UtilityError("Provider result does not bind the exact request and artifact")
    choices = {
        "availability": ("status", {"ready", "unavailable", "unknown"}),
        "dispatch": ("state", {"not_sent", "intent_recorded", "sent", "unknown"}),
        "callback": ("state", {"not_received", "success", "error", "unknown"}),
        "effect": ("state", {"unverified"}),
        "original": ("state", {"active", "retired", "unknown"}),
        "cleanup": ("state", {"held", "complete", "not_started"}),
    }
    for section, (field, values) in choices.items():
        if not isinstance(result.get(section), dict) or result[section].get(field) not in values:
            raise UtilityError(f"Invalid typed result section: {section}")
    if result["availability"].get("privilege") != "android_shell_uid_2000":
        raise UtilityError("Result must explicitly identify genuine Android shell privilege")
    original = result["original"]
    if type(original.get("retired")) is not bool or original["retired"] != (original["state"] == "retired"):
        raise UtilityError("Inconsistent original retirement result")
    if result["cleanup"]["state"] == "complete" and not original["retired"]:
        raise UtilityError("Cleanup cannot complete while its original remains owned")


def invoke(command: str, profile_path: Path, *, pin_path: Path = PIN_PATH) -> tuple[dict[str, Any], int]:
    if command not in COMMANDS:
        raise UtilityError("Unsupported developer operation")
    provider, request, expected = load_profile(profile_path, pin_path)
    with tempfile.TemporaryDirectory(prefix="kiosk-recenter-request-") as directory:
        transport = Path(directory) / "request.json"
        transport.write_text(json.dumps(request, sort_keys=True), encoding="utf-8")
        code, stdout, _stderr = _wait_for_provider([
            sys.executable, str(provider), COMMANDS[command], "--request", str(transport)])
    if _sha(provider) != expected:
        raise UtilityError("Provider source changed during observation; preserve the original evidence")
    result = _decode(stdout)
    validate_result(result, request)
    # Nonzero producer status remains visible even if it supplied a typed receipt.
    return result, code


def render(result: dict[str, Any]) -> str:
    reason = result["availability"].get("reason", "unspecified")
    return "\n".join([
        "Quest shell recenter — Kiosk developer utility",
        "Privilege: genuine Android shell UID/eUID 2000 (authorized ADB bootstrap required)",
        f"Original: {result['request_id']}",
        f"Availability: {result['availability']['status']} ({reason})",
        f"Dispatch: {result['dispatch']['state']}",
        f"Original callback: {result['callback']['state']}",
        "Visible/reference-space effect: unverified (requires separate observation)",
        f"Original ownership: {result['original']['state']}; cleanup: {result['cleanup']['state']}",
    ])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(
        "Developer-only Quest shell recenter utility. Requires genuine shell UID 2000; "
        "ordinary Kiosk/Launcher Lite APKs do not gain this authority. "
        "Recenter can move the calibrated reference frame; coordinate device custody first."))
    parser.add_argument("command", choices=COMMANDS,
                        help="readiness checks live support without recentering; status/reconcile only observe the saved original")
    parser.add_argument("--profile", required=True, type=Path, help="private reviewed Kiosk profile with exact Quest request and provider pin")
    parser.add_argument("--json", action="store_true", help="emit the unchanged typed Quest result")
    args = parser.parse_args(argv)
    try:
        if not args.json:
            print("Shell privilege required; waiting for the selected original. No automatic retries.",
                  file=sys.stderr, flush=True)
        result, code = invoke(args.command, args.profile)
        print(json.dumps(result, sort_keys=True) if args.json else render(result))
        return code
    except (UtilityError, OSError, UnicodeError) as error:
        print(f"Developer utility failed: {error}. Completion is unknown; inspect the same original with status.",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
