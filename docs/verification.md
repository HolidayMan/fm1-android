# Verification boundaries

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
