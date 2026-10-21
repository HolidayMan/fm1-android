# Verification boundaries

## Firmware update 0.3.0 — October 22, 2026

A full source rebuild passed with the eight exact current native pins. The host
workspace passed 86 tests with all native backends enabled. The dedicated Pixel
5 / API 33 arm64 emulator passed all 13 checks: eight engine routes, workspace
sample/loop/song behavior, old Swift/iOS imports, old-firmware migration, dense
rendering, and actual Activity input/audio/lifecycle/persistence.

All eight old-engine stores restore byte-identically before normal boot and
produce audible notes. Actual SLOOP 2.7.1 typed project/backup fixtures retain
musical parts, samples and global settings, then export/reopen through 2.8 with
audible loop and whole-song WAVs. Checksums and unknown package identities remain
rejected. The synthetic backup fixture's duplicated song order was normalized
using the actual old 2.7.1 native engine, with the original input preserved.
The new 2.8 envelope identity requires an updated receiving host; reverse import
into an older iOS app that pins only 2.7.1 is not claimed.

The native SLOOP upgrade probe reopened the old rich flash, all twelve objects
and arrangement byte-for-byte. It rendered 60 seconds with eight synth voices,
six drums, twelve live grains and effects. Android separately rendered 2,646,000
finite audible dense frames. Actual panel navigation displayed NOTES, edited
pitch C3 to D#3 through KNOB 2, and changed AUDIO → FILTER RESO from OFF to 3.
Skiff's actual scheduler/DSP test verified REST silence, another track advancing,
and project persistence/reload.

Dense real AudioTrack playback was measured separately for 15 seconds: OS head
660706 frames at 44100 Hz, produced/output 660480 frames, zero application-ring
shortages and zero OS underruns. The unchanged ring target is 1024 frames and
the emulator's AudioTrack buffer is 1948 frames. Two bounded waits for prepared
PCM lasted at most 1253 microseconds. Periodic saves now write immutable captured
stores on one FIFO storage thread; forced boundaries await durability. Reused
frame arrays remove the former per-frame LCD allocation. Trace measurements
identified consecutive AudioTrack write requests only five microseconds apart;
the writer now briefly waits for the independent producer before inserting real
silence. The final steady-state assertions require zero shortages/OS underruns.

Actual controller tests delayed an older checkpoint ahead of restoration and
confirmed that it could not overwrite the committed candidate. A real temporary-
file write failure preserved the active native handle and durable store; retry
then succeeded. Repeated lower-edge taps, same-key two-finger reference counts,
glissando/release, background/resume, explicit pause, edited master restoration
and Activity recreation passed. The first updated run compared all four changed
engines' pre-update copies against their previous stores; those copies remain
under Instruments/Before-0.3.0 and were retrieved for the local receipt.

APK 0.3.0 (code 3) retains org.fm1.android and the exact 0.2.0 signing certificate;
normal installation used -r without clearing data. All eight packaged files
match their source locks, all component notices are bundled, and the final source
and APK scans contain zero removed-brand matches. Exact local artifact hashes,
raw receipts and synthetic fixtures stay in ignored developer storage. No
GitHub Release, store submission, physical latency or human listening acceptance
is claimed. Upstream SCRCH changes and the portable-preview sound-bank limits
remain documented in firmware-updates.md.

## Earlier port and Kotlin baseline

The original Android port was exercised on the dedicated Pixel 5 / API 33 arm64
emulator. Eight exact compiled-native kinds booted, drew nonblank LCDs, produced
finite nonzero note PCM, and round-tripped complete edited storage. Preview
storage retained its distinct 536576-byte size.

SLOOP checks recorded a real synthetic pattern, captured typed objects, rejected
a bad workspace checksum, preserved global settings on project overlay, restored
samples, rendered audible loop/song WAVs, and checked a 3:2 duration relationship
for A1+B2 versus A1+B1 arrangements. Disposable export left the source unchanged.
Actual Activity tests injected repeated lower-edge taps, two fingers on one key,
multitouch glissando/release, background/foreground callbacks, explicit pause,
and backup restoration with an edited master setting after Activity recreation.

A warmed five-second emulator sample advanced the OS playback head by about
221206 frames at 44100 Hz, with zero additional application-ring shortages and
zero additional OS AudioTrack underruns. The configured system buffer was 1948
frames and the ring target 1024. Startup and transitions had separately recorded
shortages. Forcing the system buffer to 512 frames slowed OS consumption; the
implementation honors the device's minimum instead. These queue/counter values
do not measure end-to-end touch-to-sound latency.

Actual Android document-picker workflows saved/exported/imported a project,
canceled import review, selected/auditioned/imported a synthetic WAV sample and
saved a one-second WAV. That picker export used an empty loop and proved file
container/delivery only; the separate recorded-pattern tests proved audible
loop/song output. All six finishes were captured expanded/collapsed. Actual UI
instrument selection hid Files for Felucca and restored it for SLOOP.

An optional private synthetic exchange test imported three actual Swift/iOS
exports and applied projects through the settings-preserving overlay. Original
unchanged Swift structs decoded and restored an Android backup into a fresh
native engine, producing finite audible notes. These external test fixtures and
raw receipts are intentionally not published. Default clean-clone tests report
the exchange case as not requested unless explicit fixtures are supplied.

The standalone extraction is built independently from its own Cargo workspace
and Android sources. It contains no Swift frontend, Xcode project, original Git
history, personal data, signing credentials, firmware binary or compiled output.
Required vendored MIT Rust source and GPL adapters/notices remain attributed.

Physical Android latency, listening quality, long-session thermals, audio-focus
interruptions, unplugged outputs, exact hardware sound parity and human usability
remain unverified. A local build/test is not a store release or human acceptance.

## Kotlin host migration

The app and its instrumentation are now Kotlin under `org.fm1.android`; the
C/Rust engines are unchanged. Kotlin 2.0.21 with SDK 35 D8 passes packaging
without unsupported metadata warnings. The migrated app passed all eleven
emulator checks, including optional real Swift exchange, native engine routes,
project/sample/song behavior, real multitouch, audio output, lifecycle and
restored master settings after Activity recreation.

The first UI run found the emulator asleep, so injected events did not reach the
Activity. Waking the dedicated emulator and dismissing keyguard fixed the test
precondition; touch assertions and application behavior were not weakened. The
test script now establishes that precondition before injection. A warmed five-
second sample advanced the OS playback head by 221206 frames, with zero
additional OS underruns and application-ring shortages. Startup/transition
shortages remain separately recorded.

The new application ID installs alongside the previous app and leaves its
installation/data in place. Existing SLOOP project/backup files use the unchanged
format and can be exported from that app and imported here. No private data is
automatically read from another application.

The final Kotlin APK's twelve palette views were checked, including fixed knob
sizes and larger LCD after header collapse. Actual instrument selection hides
Files for Felucca and restores it for SLOOP. A project previously exported by the
Java app was selected through Android DocumentsUI, canceled at review, imported
and opened with a recovery backup. The WAV editor/audition and USR2 import also
completed. A one-second stereo WAV was saved through the system destination
picker; that imported loop was empty, so this specific provider file was silent.
The separate recorded-pattern instrumentation established audible loop/song
rendering. No physical listening acceptance is implied.
