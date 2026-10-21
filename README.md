# FM-1 for Android

An unofficial offline FM-1 synthesizer app for Android 8.0+ (API 26), arm64.
Eight pinned source-compiled instruments share a native Kotlin multitouch panel with
six finishes, fixed-size knobs, keyboard illumination and a collapsible header.
No account, telemetry or network permission is required by the app.

![FM-1 Android panel](docs/screenshot.png)

| Instrument | Exact release | Native kind |
| --- | --- | --- |
| SLOOP | 2.8 | 2 |
| Felucca | 1.6.1 | 3 |
| X0X | 1.1.0 | 4 |
| Melodee | 1.4.5 | 5 |
| FM1 Open portable preview | V14 | 6 |
| Skiff | 1.4 | 7 |
| FoMni | 0.4 | 8 |
| Jangada | 1.0.1 | 9 |

Only exact packages matching the linked native registry are admitted by the
Android bridge. There is no arbitrary firmware loader, runtime source compiler
or JIT in the consumer flow. Shared Rust code retains the attributed reference
emulator, but unsupported packages cannot enter that route through the app.
FM1 Open uses its portable source preview, may differ from hardware, and lacks
the original factory sound bank.

SLOOP supports named project copies, complete recovery backups, checked project
import/export, WAV trim/audition/import to USR1–4, and loop/whole-song stereo WAV
rendering from a disposable engine. Other instruments retain separate private
autosaves. Project opening preserves current global settings; restoring a full
backup restores them. Replacement saves a recovery backup first. MIDI-file
export and live recording are not offered.

## Implementation and installation identity

The Android app and instrumentation are Kotlin; JNI stays C and the shared
firmware engines stay C/Rust. The neutral application ID is `org.fm1.android`.
Changing the app ID installs a separate app alongside an earlier installation;
it does not automatically adopt that app's private data or remove it. To move
SLOOP work, export project copies or complete workspace backups from the earlier
app, then import them with Projects and files in this app. Other instruments'
private autosaves are not automatically transferred. The project/backup schema
is unchanged. SLOOP 2.7.1 project and backup exports remain accepted by the 2.8
engine; newly exported files identify 2.8.

## Firmware update 0.3.0

The October 22, 2026 stable-release audit updates SLOOP to 2.8, Melodee to 1.4.5,
Skiff to 1.4, and the FM1 Open portable preview to V14. Felucca, X0X, FoMni and
Jangada already match their latest stable releases. See [update details and
compatibility](docs/firmware-updates.md).

Existing private autosaves retain their filenames and settings. Before the first
restore by each changed engine, the app preserves its previous raw save under
`Instruments/Before-0.3.0/`; failed restores leave the original and backup in place.
SLOOP's format remains compatible, although upstream 2.8 intentionally removes
the SCRCH backspin/rewind sounds. New Skiff SONG REST entries require Skiff 1.4.

## Build

The source repository includes no firmware binaries, generated firmware assets,
APKs, signing credentials or upstream firmware checkouts. Build preparation is
an explicit developer operation; the app never downloads or compiles engines.

Validated build host: Apple Silicon macOS, Java 19, Rust, Python 3.12+, Android
SDK platform 35/build-tools 35.0.0, NDK 27.2.12479018 and host LLVM 20. Linux NDK
host discovery is supported but has not been validated. Kotlin 2.0.21 and its
standard library are pinned by SHA256; firmware resource generation needs
build-only Pillow 13.0.0 with RAQM and fonttools. Exact generation-library versions and
resource hashes are recorded in the lock files.

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install 'Pillow==13.0.0' fonttools
python -c 'from PIL import features; assert features.check("raqm")'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export FM1_NATIVE_LLVM_BIN="/opt/homebrew/opt/llvm/bin" # LLVM 20
rustup target add aarch64-linux-android
python scripts/prepare.py
./Android/build.sh --core
```

Preparation clones the exact source pins, runs upstream resource generators,
and downloads size/SHA256-checked release packages into ignored `Research/`.
Existing changed checkouts or mismatching packages fail instead of being reset
or overwritten. See [detailed prerequisites and cache layout](docs/build.md).
The shared crate retains its historical `fm1-ios-core` name to avoid ABI churn;
there is no iOS frontend in this repository.

The build bootstraps the exact Kotlin compiler into ignored `.tools/` and checks
its archive SHA256. It bundles only `kotlin-stdlib.jar`, with its Apache-2.0
notices; no reflection, coroutine, Gradle or Maven library is added.

Output: `Android/build/FM1-android-arm64.apk`, signed using a locally generated,
ignored development key/password. Preserve those local files for updates. An
independent clone creates a different signer. A binary release is not yet
published; this repository supplies source and build instructions.

```sh
adb install -r Android/build/FM1-android-arm64.apk
./Android/test.sh
```

The SDK-only instrumentation app uses the same local signer. Ten default cases
exercise all engines, SLOOP files/audio, and real Activity input/lifecycle/state.
The additional iOS exchange case is optional and requires explicit external test
fixtures through `FM1_EXCHANGE_FIXTURES`; no private export is included. Tests
create synthetic projects in the dedicated emulator's app storage.

## Verification and limits

Version 0.3.0 passed 86 host tests and all 13 emulator checks, including old save
migration, dense 60-second native playback, a separate 15-second real AudioTrack
window with zero shortages/OS underruns, and failed-write recovery. Actual panel
navigation exercised the new NOTES and FILTER RESO controls.

The original Android port passed all eight native boot/LCD/note/flash checks,
SLOOP sample/loop/song checks, actual multitouch and Activity lifecycle/state
checks, Android document-picker workflows, and actual Swift project exchange in
both directions. Six finishes were inspected expanded/collapsed; this screenshot
uses synthetic test state. The extraction's standalone build is checked
separately. See [verification boundaries](docs/verification.md).

The panel follows the current iOS layout; Android uses platform dialogs rather
than iOS glass sheets. Every engine belongs to a serial worker; a separate
AudioTrack writer consumes a bounded pre-rendered ring. No synthesis runs in
the output thread. App backgrounding releases held input and pauses generation.
An explicit pause survives ordinary background/foreground transitions.

Emulator playback-head progress, queue sizes and underflow counters do not prove
physical Android latency, listening quality, long-session thermal behavior,
interruption/route handling or hardware sound parity. Physical-device and human
acceptance remain unverified. No store submission is included.

## License and attribution

Project-owned code is GPL-3.0-only; the vendored fm1-emu crates remain MIT.
Native adapters derive from Virtual FM-1 and retain upstream attribution.
Individual firmware resources and components retain their own terms. See
[NOTICE](NOTICE), [provenance](docs/provenance.md), `licenses/`,
`Core/native/licenses/` and the public source locks. Full bundled notices are
available inside the app through About → Credits and licenses.
