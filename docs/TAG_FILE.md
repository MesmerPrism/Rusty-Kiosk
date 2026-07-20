# Tag file

Rusty Kiosk hotloads `app-tags.v1.json` from its app-specific external files
directory. The app creates an empty file on first launch and watches the
containing directory for create, close-write, move, and delete events.

## Schema

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

`name` is required. `package` is optional. `tags` is an array of short strings.
The app normalizes tags to lowercase, trims whitespace, removes duplicates, and
rejects an invalid schema or oversized file.

## Matching

1. A record with `package` matches that exact installed package.
2. A name-only record matches every installed launchable app whose displayed
   label is equal after case and whitespace normalization.
3. A record that matches no installed app becomes a synthetic catalogue entry
   labeled **Not installed**.
4. Tags added in the panel are saved with both name and package when the
   selected app is installed, making the new record unambiguous.
5. Search covers display name, package name, and tag. Tag filtering includes
   missing synthetic entries.

The file contains low-rate user organization data only. It never stores APK
paths, activities, signing data, permissions, commands, or binary payloads.
