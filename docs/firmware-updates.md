# Firmware update 0.3.0

Stable releases checked October 22, 2026, directly against the public upstream
release/tag records. Source commits, generated resources, firmware package
size/SHA256 and component notices are pinned in `Core/native/*.lock.json`.

| Instrument | Included release | Result |
| --- | --- | --- |
| [SLOOP](https://github.com/isod89/sloop-fm1/releases/tag/v2.8) | 2.8 | Updated from 2.7.1 |
| [Felucca](https://github.com/hugelton/Felucca/releases/tag/v1.6.1) | 1.6.1 | Already current |
| [X0X](https://github.com/charlesvestal/fm1-x0x/releases/tag/v1.1.0) | 1.1.0 | Already current |
| [Melodee](https://github.com/keremimo/melodee/releases/tag/v1.4.5) | 1.4.5 | Updated from 1.4.3 |
| [FM1 Open preview](https://github.com/m-vave/fm1_open/releases/tag/V14) | V14 | Updated from V13 |
| [Skiff](https://github.com/illuminus5/skiff/releases/tag/v1.4) | 1.4 | Updated from 1.3.2 |
| [FoMni](https://github.com/charlesvestal/fm1-fomni/releases/tag/v0.4) | 0.4 | Already current |
| [Jangada](https://github.com/zednaked/jangada/releases/tag/v1.0.1) | 1.0.1 | Already current |

SLOOP 2.8 adds the NOTES editor, drum-step sheets, filter resonance and drum FX
filter support. Its native host also restores the new persisted punch-in FX fade
setting at boot. Upstream reports no project format change from 2.7. Existing
Android/iOS 2.7.1 `.fm1project` and `.fm1backup` envelopes are explicitly admitted,
checksummed and restored through the current engine; other package identities
remain rejected. New exports identify 2.8. An older app that requires the exact
2.7.1 envelope hash must be updated to import those new exports.

Upstream intentionally replaced the SCRCH backspin and rewind zones with the
first scratch mapped across the keyboard to make room for the editor. Existing
projects remain readable, but those built-in sounds can differ. Imported user
samples are independent and remain part of typed backups.

Melodee 1.4.5 keeps its 1.4.3 project/storage formats. Skiff 1.4 adds SONG REST
and per-sample level/sends; old projects remain accepted, but a project containing
REST cannot be opened on Skiff 1.3.3 or earlier. The Android adapter targets the
FM-1 panel; the separate SMK-37 hardware port is not included.

FM1 Open is still a portable source preview with synthesized-demo samples.
The current official hardware package and browser-WASM metadata are retained
as provenance, but this build does not claim binary browser or hardware parity,
nor the hardware's original factory bank. Old preview saves get their own
pre-update copy before restoration and never seed a different engine.

Each changed engine keeps its exact pre-update private raw save in
`Instruments/Before-0.3.0/` before first restoration. This copy is retained even
when restoration fails. The update keeps the same application ID and local
signing key; normal installation is `adb install -r`, with no data clear.

For optional migration checks, supply synthetic fixtures captured from APK 0.2.0:

```sh
FM1_MIGRATION_FIXTURES=/path/to/old-synthetic-fixtures ./Android/test.sh
```

The directory contains `kind-2.flash` through `kind-9.flash`, the recorded
`sloop-2.7.1.fm1project` and `.fm1backup`, and `dense-2.7.1.flash` from the local
native stress probe. They are developer-only test inputs and are never included
in the consumer APK or public repository. The optional baseline capture flag
`FM1_CAPTURE_BASELINE=true` requires the old 0.2.0 app to be installed; it refuses
to label current-engine output as an old fixture. Default clean-clone checks do
not claim migration evidence when these external fixtures are absent.
