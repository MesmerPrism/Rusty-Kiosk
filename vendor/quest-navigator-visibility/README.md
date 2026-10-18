# Exact Quest Navigator visibility source

This directory contains the unmodified source/license inventory in
`UPSTREAM.json`, pinned to the merged Rusty Quest commit recorded there.
Rusty Quest owns the component; make changes upstream and deliberately update
the complete pin/inventory. Do not maintain another implementation here.

The `navigator-visibility` Android library compiles the runtime source against
separately compiled vendor ABI declarations. The declarations remain compile-only
and must not enter the AAR or APK. No vendor binary is included.

Kiosk owns opt-in selection, selected target, escape, journal, lifecycle and the
fixed broadcast. The library owns availability, observation and its controller
handle only. See `docs/VISIBILITY_MODE.md` at the repository root.
