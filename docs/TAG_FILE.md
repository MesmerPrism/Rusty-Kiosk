# Tag file

Rusty Kiosk hotloads `app-tags.v1.json` from its app-specific external files
directory. The app creates an empty file on first launch and watches the
containing directory for create, close-write, move, and delete events.

## Schema

Legacy v1 stores passive searchable tags only and therefore means **Any** launch
requirement. The first explicit requirement edit upgrades the complete document
to strict v2:

```json
{
  "schema": "rusty.kiosk.app_tags.v1",
  "apps": [
    {
      "name": "Example Installed App",
      "package": "com.example.optional",
      "tags": ["demo", "movement"]
    },
    {
      "name": "App Planned For Another Headset",
      "tags": ["demo"]
    }
  ]
}
```

```json
{
  "schema": "rusty.kiosk.app_tags.v2",
  "apps": [
    {
      "name": "Offline Demo",
      "package": "com.example.demo",
      "tags": ["demo", "movement"],
      "requirements": ["wifi-off"]
    }
  ]
}
```

`name` is required. `package` is optional. `tags` is an array of short strings.
The app normalizes tags to lowercase, trims whitespace, removes duplicates, and
rejects an invalid schema or oversized file.

v2 accepts zero or one compiled requirement: `wifi-on` or `wifi-off`. Zero is
the explicit **Any** state. Unknown values, duplicates, both Wi-Fi states,
duplicate app identities, unknown fields, and wrong JSON types fail closed.
The requirement field is separate from `tags`: adding, searching, or deleting
a tag named `wifi-on` never changes launch behavior, and tag editing preserves
an existing requirement.

Before either Normal or Kiosk launch, Rusty Kiosk reads ordinary Wi-Fi through
`WifiManager.isWifiEnabled`. It never changes Wi-Fi and never uses the Wi-Fi ADB
setting. If unmet, it opens Android's fixed Wi-Fi settings surface and keeps one
two-minute pending binding. Return revalidates target, installed signer/version,
launch mode, document digest, and requirement. Cancellation, expiry, process
restart, app disappearance/update, or document change prevents launch.
Pressing launch again for the exact pending binding remains waiting without
reopening Settings or extending the deadline. Pressing launch after the binding
changes cancels the old request and fails closed instead of switching targets.

## Matching

1. A record with `package` matches that exact installed package.
2. A name-only record matches every installed launchable app whose displayed
   label is equal after case and whitespace normalization.
3. A record that matches no installed app becomes a synthetic catalogue entry
   labeled **Not installed**.
4. Tags added in the panel are saved with both name and package when the
   selected app is installed, making the new record unambiguous.
5. Unquoted search splits on separators and requires every term to match the
   same entry's display name, package name, or tags. Double quotes group one
   contiguous phrase that must occur within a single field; separators inside
   the phrase normalize to spaces. Tag filtering includes missing synthetic
   entries.

The file contains low-rate user organization data only. It never stores APK
paths, activities, signing data, permissions, commands, or binary payloads.

An authorized desktop ADB host uses the `DUMP`-protected host-provider v4 tag
methods instead of depending on raw access to Android's app-specific external
directory. The provider reads or writes only this document in ordered 6 KiB
Base64 chunks, caps the complete file at 256 KiB, verifies SHA-256, validates
the schema, and atomically activates a valid replacement. The directory watcher
hotloads that replacement without restarting Rusty Kiosk. Host tools must not
supply or infer a device path or treat the tag file as a command channel.
