# Foreground signal

## Decision

Rusty Kiosk supports one optional protocol-v2 advisory from the currently
armed target application. It can reduce the delay between Android reporting
application-level foreground loss and Kiosk requesting the same bounded
recovery already used by Accessibility.

The advisory is not a Home-button signal. Accessibility remains enabled,
observes Horizon package/window transitions, and solely owns exact or generic
Home counting and the Triple-Home escape.

## Client integration

Add the engine-neutral `foreground-signal-client` Android library to the target
application. The library contributes protocol metadata and a package query for
the fixed Kiosk provider. Call it only after an app-owned lifecycle or engine
integration has confirmed that the application as a whole lost foreground
authority:

```java
if (!applicationHasForegroundAuthority) {
  ForegroundSignalClient.notifyApplicationForegroundLost(
      applicationContext, "engine-application-focus");
}
```

`Activity.onTopResumedActivityChanged(false)` is not an application-level
signal: it also occurs during same-package Activity transitions, and Android
does not bound how long the successor can take to become top-resumed. Do not
forward that callback directly or infer application loss with a fixed timer.

The Boolean result is diagnostic only:
`false` means Kiosk is absent, the guard is not armed, the caller is rejected,
or Accessibility is unavailable. Callers never own recovery.

The library contains no Spatial SDK, OpenXR, Unity, Godot, Unreal,
Accessibility, service, launch, or recovery-policy dependency.

## Admission

At kiosk launch, Kiosk records:

- a cryptographically random, nonzero per-arm generation;
- the exact target package and activity;
- protocol version 2 from installed application metadata;
- the target's canonical signing-certificate lineage;
- PackageManager's last-update time and version code for that installation.

At each provider call, Kiosk derives the immediate Binder UID and package and
re-reads installed metadata and signing certificates. Admission requires:

- the calling package equals the armed target;
- the UID resolves to exactly that one package;
- requested, armed, and installed protocol versions all equal 2;
- the complete call-time signing lineage equals the launch-time lineage;
- call-time last-update time and version code equal the launch-time values;
- there is exactly one current signer.

Missing or ambiguous identity, shared UID, multiple current signers, package
replacement, signer rotation, metadata drift, and stale generations fail
closed. No signing digest is accepted from the caller.

## Recovery behavior

The provider queues a generation-bound foreground-loss record to the active
Accessibility service. The service rechecks generation, package, and protocol
before calling `observeForegroundLoss`.

Direct and Accessibility observations share one recovery engine:

- one episode has at most three atomically claimed launch attempts;
- repeated foreground-loss signals are quieted;
- target confirmation cancels pending recovery;
- stale queued work cannot cross a disarm or re-arm;
- later Accessibility Home observations remain eligible to count exactly once;
- only Accessibility can cause the third-Home return to Rusty Kiosk.

Targets that do not advertise protocol v2 use the Accessibility-only route.

## Validation boundary

Host validation covers protocol parsing, exclusive-UID admission, signing
lineage, generation allocation, rejection of Activity-level inference,
repeated and stale signals, recovery races, manifest boundaries, and dependency
neutrality. It does not claim headset latency or physical Home-button timing.
Any later device measurement belongs in private serial-scoped evidence and
requires the normal attended Quest workflow.
