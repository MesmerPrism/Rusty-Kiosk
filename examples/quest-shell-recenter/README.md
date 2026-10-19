# Quest shell recenter developer utility

This standalone host utility exposes the fixed Rusty Quest shell recenter
adapter through a small console interface. It is a developer/operator path
requiring an already authorized ADB connection and genuine Android shell
UID/eUID 2000. It is separate from Kiosk releases and Store/Lite behavior.
Neither ordinary APK permissions nor the Kiosk setup helper provide this
authority. The utility does not install, launch, stop, or modify a Kiosk APK.

The maintained AppFunction implementation, shell identity construction,
firmware/permission admission, durable intent, original callback ownership,
and owned-stage cleanup belong to
[Rusty Quest](https://github.com/MesmerPrism/rusty-quest). Kiosk invokes its
exact pinned provider instead of copying the implementation or adopting the
unpublished menu companion. [UPSTREAM.json](UPSTREAM.json) records the reviewed
source revision and provider SHA-256. No generic shell, Android intent,
AppFunction target, reflection operation, or request extra is exposed here.

## Prepare one private request

Use Python 3.10 or newer. Obtain the exact Quest revision in `UPSTREAM.json`
and build its `apps/shell-recenter-android` adapter with the owner-provided
`tools/Build-ShellRecenter.ps1`. Keep the resulting dex JAR, build receipt,
profile, and evidence in ignored/private storage. These artifacts are not
Kiosk APK or release assets. There are no additional Python dependencies.

Create a private profile with exactly three top-level fields:

```json
{
  "schema": "rusty.kiosk.shell_recenter.profile.v1",
  "provider": {
    "path": "/absolute/quest/tools/shell_recenter/quest_shell_recenter.py",
    "sha256": "<provider SHA-256 from UPSTREAM.json>"
  },
  "request": {
    "schema": "rusty.quest.shell_recenter.request.v1",
    "request_id": "<fresh 32 lowercase hexadecimal characters>",
    "nonce": "<fresh 32 lowercase hexadecimal characters>",
    "adb": "<absolute authorized ADB executable>",
    "canonical_serial": "<current canonical headset serial>",
    "selector": "<current exact authorized transport selector>",
    "boot_id": "<current canonical boot UUID>",
    "operator_id": "<current operator identity>",
    "artifact": {
      "path": "<absolute reviewed adapter dex JAR>",
      "sha256": "<artifact SHA-256>",
      "bytes": 1
    },
    "source": {
      "revision": "<exact revision from UPSTREAM.json>",
      "tree": "<exact Quest source tree>",
      "source_id": "<source identity from the Quest build receipt>"
    },
    "firmware": {
      "fingerprint": "<current supported Android fingerprint>",
      "vrshell_sha256": "<current supported shell APK SHA-256>",
      "framework_sha256": "<current supported framework SHA-256>"
    },
    "output_dir": "<fresh absolute private evidence directory>"
  }
}
```

Replace every placeholder, including the actual artifact byte count. Quest
owns the closed request schema and rejects expanded/malformed requests before
device effects. Kiosk additionally checks the reviewed provider bytes, fixed
provider source layout, source revision/tree, matching result identity,
complete request digest and artifact commitment. The
profile is local operator configuration, not a target-discovery mechanism or
a device-operation authorization. Historical serials, firmware, source pins,
and boot IDs are not current admission evidence.

## Console controls and typed results

From the Kiosk repository root:

```powershell
python examples/quest-shell-recenter/recenter.py --help
python examples/quest-shell-recenter/recenter.py readiness --profile <private-readiness-profile.json>
python examples/quest-shell-recenter/recenter.py recenter --profile <private-recenter-profile.json>
python examples/quest-shell-recenter/recenter.py status --profile <same-private-recenter-profile.json>
python examples/quest-shell-recenter/recenter.py reconcile --profile <same-private-recenter-profile.json> --json
```

| Control | Behavior |
| --- | --- |
| `readiness` | Runs the owner's live shell identity, privilege, firmware, permission and API checks without dispatching recenter. It requires its own fresh request/output identity. |
| `recenter` | Requests one fixed named recenter through the shared provider. Use a different fresh request/output identity after readiness and current device custody are established. |
| `status` / `reconcile` | Reads only the exact original's retained local status/result through the provider's passive reconciliation route. Neither performs live ADB operations nor launches another helper. |
| `--json` | Emits the unchanged typed Quest result after identity validation; preserves the producer's exit status. |

The text interface always shows required shell privilege, effective
availability and its reason, dispatch state, original callback state, effect
verification, original ownership, and cleanup. `unavailable` and `unknown`
remain explicit. A successful callback still displays **effect: unverified**;
only a separately recorded reference-space/pose or wearer observation can
establish the actual effect. The JSON result retains the owner's callback
error payload and evidence fields.

The Quest provider writes intent before its single dispatch and keeps the
original until its actual callback, full output EOF and process reap. Kiosk
does not impose a timeout, cancel, kill, retry, or substitute another route.
The utility isolates the provider's process group and waits through a console
interrupt rather than terminating a dispatched original. On an observer or
transport failure, retain the profile and output directory and use **status**
for that same original. An expired observer deadline, pending callback,
missing result, or changed source is never permission to create another
recenter request. Duplicate/replay admission remains the shared provider's
responsibility; Kiosk does not reinterpret its ledger.
Quest's fsynced per-user original-identity journal binds the request ID to the
whole request and operation before dispatch. Changing the output directory or
nonce, or deleting an old evidence directory, cannot make that original unused.

## Device custody and cleanup

Before any live invocation, follow the Quest workflow and exact device-owner
handoff. Confirm current route, boot, finite window, wearer/operator, foreground
application, calibrated-scene implications, resource lease and desired final
state. Recenter may change a running application's calibrated reference frame.
Do not assume another test ending at an estimated time grants custody.

This utility does not change Guardian policy, remap Home, close menus, grant
permissions, restart ADB, reset/reboot a headset, or install an APK. Keep
unrelated apps, watchdogs, shared ADB and other headsets intact. The shared
provider removes only its own confirmed-retired stages. Preserve held and
unknown originals with their actual receipts; never label a crash or reboot as
graceful retirement. If an independent companion is already present, APK
replacement still requires its own disarm, original settlement, stop,
final/process/reader/ACTIVE absence and fresh-bootstrap protocol.

Return exact device custody to its owner with callback and effect observations
reported separately. A host fixture or a successful build is not headset or
wearer-effect acceptance.

## Host verification

```powershell
python -B -m unittest discover -s examples/quest-shell-recenter/tests -v
pwsh -NoProfile -File tools/check_repo.ps1
```

The production consumer/process tests exercise the actual CLI-to-producer
subprocess boundary, exact body/operation forwarding, readiness and passive
status routing, unavailable privilege/firmware, late error payloads, pending
originals, receipt/source mismatches, interrupt custody and exception cleanup.
Their producer is an explicit process-conformance fixture; they do not execute
an AppFunction or prove an end-to-end effect. The shared Quest owner supplies
adapter/producer tests and separate actual device evidence. The complete Kiosk
gate also verifies the existing APK and release boundaries.

For actual passive producer/consumer checks, use
`tools/check_repo.ps1 -QuestShellRecenterUpstreamRoot <exact-Quest-checkout>`
or set the process-local `QUEST_SHELL_RECENTER_UPSTREAM_ROOT` for the focused
Python command. The tests verify the
provider SHA-256 and source revision/tree, then invoke
only `reconcile` against synthetic local STATUS/RESULT documents with a
nonexistent ADB executable. It proves pending/final/error/hash wire behavior
through the real Quest producer and Kiosk caller without device operations;
it is still host conformance rather than a recenter effect result.
