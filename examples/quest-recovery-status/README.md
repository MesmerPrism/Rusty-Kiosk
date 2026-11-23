# Quest recovery status panel

Use this standalone developer panel to review a captured recovery report for one
headset and boot. It reads a selected JSON file in memory and displays fixed
labels and observation times.

From the repository root, open it directly in a desktop browser:

```powershell
Invoke-Item .\examples\quest-recovery-status\index.html
node --test examples/quest-recovery-status/recovery-status.test.cjs
```

Choose **Load sample** to inspect one of the three simulated scenarios. Use
**Open recovery report** for actual evidence and **Clear** when done. Local
reports can contain private details. Keep those files in private storage and
use only synthetic evidence in examples, screenshots, and commits.

## Report format

Use `rusty-kiosk.recovery-status.v1` as `schema`. The `context` identifies the
current headset and boot with its canonical hardware `serial`, kernel `boot_id`,
Android `boot_count` when known, and capture time `observed_at_ms`. Use Unix
milliseconds from the actual capture. Select the exact device before collecting
each result, including when the ADB transport endpoint differs from the hardware
serial. The panel accepts alphanumeric hardware serials and permits hyphens or
underscores for fixture identifiers. Retain the kernel boot ID from the same capture. The wrapper records the
operator's evidence binding. Parsing it does not authenticate who created it.

Each optional source is `{serial, boot_id, observed_at_ms, receipt}`. Use the
capture's actual identity and time. Observations turn stale after two minutes
while the page is open. Evidence qualifies only for the selected headset and
boot.

| Source key | `receipt` content and resulting reading |
| --- | --- |
| `kiosk` | Completed and accepted Kiosk `status` or `check-setup-helper` result in `rusty.kiosk.cli_result.v1` format. Its `recorded_at_ms` and matching `state.last_boot_request.boot_count` qualify the boot outcome, such as `waiting_for_wifi` or `requested`. [Kiosk CLI contract](../../docs/CLI.md#command-vocabulary) |
| `qfm` | The developer tool's `kiosk status --json` object. The panel uses the completed, accepted `kiosk.State.WifiAdbEnabled` reading. This is the Android setting, with separate protocol authorization and listener evidence. [QFM Kiosk operator](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/bda27c5f753ca62624ed206286e4473d4ea83211/docs/rusty-kiosk-integration.md) |
| `approval` | A wearer observation of the protected Horizon prompt. Use `{ "source": "wearer-observation", "state": "prompt-visible" }`, `approved`, or `declined` as actually observed. A boot request alone leaves this row unknown. The wearer handles the prompt. [Kiosk control guide](../../docs/USER_CONTROL.md#status-model) |
| `shell` | A separately verified Wi-Fi ADB shell readback with `transport: "wifi-adb"`, `serial`, `boot`, `uid: "2000"`, and `verified: true`. Successful shell readback on the exact Wi-Fi transport qualifies this row. [Attended transport qualification](https://github.com/MesmerPrism/meta-quest-agent-workflow/blob/39a4641d34d806a7604e61dd5dc9e810d86608df/docs/boot-wireless-adb-qualification.md) |
| `watchdog` | The QFM watchdog `status` JSON. Active requires the matching recorded `boot_id`, a matching startup JAR hash, `identity_matches`, `lease_held`, `owner_active`, `active`, and `fresh`. Physical sensor-lock readiness requires a separate observation. [QFM watchdog status](https://github.com/MesmerPrism/QuestIonAble-File-Manager/blob/bda27c5f753ca62624ed206286e4473d4ea83211/helpers/quest-proximity-watchdog/README.md#status-and-readback) |

For example, a report can contain only the context and one actual
prompt observation. Missing sources remain unknown. The following uses an old,
synthetic timestamp so opening it later reports stale evidence.

```json
{
  "schema": "rusty-kiosk.recovery-status.v1",
  "context": {
    "serial": "demo-headset",
    "boot_id": "00000000-0000-4000-8000-000000000100",
    "boot_count": 12,
    "observed_at_ms": 1767268800000
  },
  "approval": {
    "serial": "demo-headset",
    "boot_id": "00000000-0000-4000-8000-000000000100",
    "observed_at_ms": 1767268800000,
    "receipt": { "source": "wearer-observation", "state": "prompt-visible" }
  }
}
```

ADB access, device settings, and watchdog controls remain with their owner
tools. The wearer handles any Horizon prompt. Reports stay in page memory
until cleared or closed. The fixtures exercise the desktop projection.
Headset behavior requires the separate attended checks.
