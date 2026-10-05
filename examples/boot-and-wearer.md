# Boot and wearer-aware launch

An optional boot utility can be a separately configured application or a
capability embedded in a purpose-built app. Neither placement belongs in the
fixed-target Rusty Kiosk Launcher. The Launcher has one Activity and excludes
boot receivers, services, background launch authority, and general target
selection; see its [contract](../docs/KIOSK_LAUNCHER.md).

## Integration record

- **Trigger:** state which boot broadcast is received and which component owns
  any subsequent wait. A broadcast receiver itself is not a long-lived worker.
- **Target:** persist an explicit user selection, then revalidate its exact
  exported front door and installed identity at launch time. Missing or changed
  targets stop without falling back to a different app.
- **Signals:** keep Android boot completion, display interactivity, wearer
  observation, Activity visibility, and XR readiness separate. A platform or
  firmware-specific headset property is an observation, not a portable wearer
  or application-ready guarantee.
- **Policy:** specify whether to wait for the wearer, how cancellation works,
  and which deadline applies in each mode. An unbounded wearer wait must be
  described as such until an explicit policy changes it.
- **Effect:** distinguish an attempted `startActivity` call from the selected
  app becoming active and ready. Background activity-start rules depend on
  device OS and target SDK; do not add privileges merely to make an example
  appear successful.

Reusable Android observation and dispatch code belongs in Rusty Quest after
its boundary and a second consumer or neutral harness are reviewed. The host
app owns the visible preference, selected target, readiness criteria, and
resulting launch policy. Keep any source prototype's package/signing lineage,
migration defaults, and private target out of a public implementation.

## Host-only checks before device work

Review the manifest for only the declared receiver, service, and permissions;
check target selection/revalidation, cancellation, deadlines, and restart
behavior with injected signals. Inspect the produced APK manifest and signing
identity. Record the actual Android API, target SDK, and relevant foreground
service/background activity-start rules. These checks do not prove that a
Quest firmware will admit a background launch or that the target reaches XR
readiness. Live proof requires an exact headset and app-side effective-state
evidence under the device workflow.
