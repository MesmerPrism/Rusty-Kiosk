# Panel preview workflow

## Purpose

Rusty Kiosk has two complementary desktop views of its one Spatial SDK panel:

- **Browser projection** is interactive and optimized for rapid layout,
  catalogue-state, tag, and onboarding-flow iteration.
- **Native Android** compiles and renders the source-bound Android design
  projection with Layoutlib through Paparazzi. It is the desktop visual
  authority; the Quest production panel itself is a native Android View tree.

The Quest APK remains the final authority for compositor filtering, apparent
angular size, controller/hand-pointer behavior, keyboard behavior, and scene
placement. Neither desktop route is packaged as a WebView or controls a real
headset.

## Shared dimensions

The production panel is 1.55 × 1.05 m at 700 dp per meter, giving a logical
surface of 1085 × 735 dp. Meta Spatial SDK documents 288 dpi as the default
dp-layout density, so the native preview renders 1953 × 1323 pixels. The
browser uses one CSS pixel per Android dp and scales the complete canvas only
to fit the desktop viewport.

The checked contract is
`references/rusty-kiosk-panel-contract.v1.json`. Accepted geometry, palette,
control tags, scenarios, and renderer roles must change together across the
production native source, browser projection, native renderer, and tests.

Meta’s current panel-resolution guide explains both the
`DpPerMeterDisplayOptions` calculation and matching Compose previews:
https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-resolution/

## Interactive browser projection

Start the local server from the repository root:

```powershell
pwsh -NoProfile -File .\tools\Start-RustyKioskPanelBrowserPreview.ps1
```

Open:

```text
http://127.0.0.1:8767/tools/rusty-kiosk-panel-browser-preview/
```

The projection supports:

- multi-term app search across labels, packages, and tags;
- tag filters and tag add/remove interactions;
- explicit Any / Wi-Fi on / Wi-Fi off launch-requirement controls;
- launchable, installed-without-front-door, and not-installed states;
- guard-enabled and guard-setup states;
- setup-helper readiness, Wi-Fi ADB, Accessibility, and Meta Home status;
- reversible user-control simulations, including explicit setup and revocation;
- normal/kiosk launch simulations;
- deterministic JSON state export/import;
- native Android and 50% comparison views after native generation.

Selecting the 50% comparison view resets the browser projection to the selected
canonical scenario. If you interact with the projection afterward, its status
states that the browser and native states have diverged; select the comparison
view again to realign them.

All app/package records are synthetic. Import accepts only the bounded preview
schema and never reads the headset catalogue or production tag file.

Useful clean visual routes include:

```text
?scenario=catalog-ready&view=browser&capture=1
?scenario=tag-filter-missing&view=browser&capture=1
?scenario=guard-setup&view=browser&capture=1
```

These routes are suitable for iterating onboarding compositions. Generated
screenshots remain ignored and should be reviewed before any deliberate
publication.

## Native Android rendering

Generate all authoritative desktop-native scenarios:

```powershell
pwsh -NoProfile -File .\tools\Export-RustyKioskNativePanelPreview.ps1
```

The isolated host points its Android source set directly at these production
files:

- `CatalogModels.kt`
- `RustyKioskPanel.kt`
- `RustyKioskPanelContract.kt`
- `RustyKioskTheme.kt`

The production search and tag fields are direct Android `EditText` descendants
of a Spatial SDK `LayoutXMLPanelRegistration`. They enable soft input on focus
and explicitly request the Quest IME from both focus and click. Because Meta
hosts the panel on a virtual display, the request obtains a display-scoped
`InputMethodManager` from the attached field instead of the Activity's display
0 instance. A rejected request receives one bounded retry; diagnostics contain
display, attachment, token, attempt, and acceptance state but never field text.
The main Spatial activity also uses `FLAG_ALT_FOCUSABLE_IM`: Meta's compatibility
route for the case where Android serves the field and reports the IME as shown,
but the immersive compositor does not make the keyboard surface visible.
The native renderer verifies the source-bound design projection; only a real
Quest run proves Meta keyboard opening and text entry.

Paparazzi supports direct Compose snapshots without an emulator. The exporter
records `catalog-ready`, `tag-filter-missing`, and `guard-setup`, verifies each
1953 × 1323 raster, and writes a manifest containing the source commit, dirty
state, individual source hashes, and image hashes. Paparazzi documentation:
https://cashapp.github.io/paparazzi/

Outputs live under:

```text
artifacts/rusty-kiosk-native-panel-preview/
```

They are ignored by Git. Refresh the browser projection and use its **View**
selector to inspect the native image or compare it against HTML.

## Validation

Fast contract and interaction checks:

```powershell
pwsh -NoProfile -File .\tools\Test-RustyKioskPanelPreview.ps1
```

Include native rendering:

```powershell
pwsh -NoProfile -File .\tools\Test-RustyKioskPanelPreview.ps1 -RenderNative
```

A successful desktop render does not prove live Spatial input or placement.
Use the attended headset checklist in `docs/VALIDATION.md` for those claims.
