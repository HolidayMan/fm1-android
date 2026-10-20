# Reproducible local build

## Tools

Validated on Apple Silicon macOS with Java 19, Python 3.12+, Rust, host LLVM 20,
Android SDK platform 35/build-tools 35.0.0 and NDK 27.2.12479018. A Java 17+
toolchain is required by current Android build tools. Runtime uses only platform
Kotlin/JVM APIs, the Rust standard library, and the pinned native sources.
Kotlin standard library is the only additional runtime; no Gradle/Maven library
or Cargo package download is required.

Install the SDK command-line tools normally, then use the installed sdkmanager:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018'
rustup target add aarch64-linux-android
```

Set ANDROID_HOME to the SDK root and ANDROID_NDK_HOME to that exact NDK. NDK host
binaries are discovered under toolchains/llvm/prebuilt rather than assumed to be
Darwin. Linux compilation is configured but has not been tested. Windows is not
validated by these Bash scripts.

Set FM1_NATIVE_LLVM_BIN to a matching clang and llvm-link from LLVM 20. Multi-unit
firmware is combined before per-instance state rewriting, so the linker and IR
compiler must match. The NDK clang handles other translation units. Exact source
and resource fingerprints are checked before native compilation.

## Kotlin compiler

`scripts/kotlin-toolchain.sh` pins the official Kotlin 2.0.21 compiler ZIP to
SHA256 `0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a`.
The build downloads it explicitly on first invocation into ignored `.tools/`,
validates the archive before using it, and compiles JVM 1.8 bytecode. D8 includes
only the bundled Kotlin standard library in the consumer APK; the test APK uses
the target app's runtime. Kotlin reflection and coroutines are not dependencies.

The compiler line matches the D8 bundled in SDK build-tools 35.0.0. Kotlin 2.0
requires D8/R8 8.5.10 or newer; the validated SDK tool reports 8.6.2-dev.
See [Android's compatibility table](https://developer.android.com/build/kotlin-support)
and the [official compiler release](https://github.com/JetBrains/kotlin/releases/tag/v2.0.21).

## Prepare sources and assets

Use an active Python virtualenv with Pillow 13.0.0 supporting RAQM and fonttools.
The family lock records the validated Pillow/FreeType/RAQM versions. Generated
font/image bytes are pinned; another rendering-library version may correctly
fail verification. Restore the recorded build environment rather than changing
expected hashes to accept a different asset.

```sh
python scripts/prepare.py
```

This explicit command executes the following existing source preparers, then
fetches package records from Core/native/packages.lock.json:

```sh
python Core/native/prepare.py "$PWD/Research/NativeSloop/upstream-sloop-2.7.1"
python Core/native/prepare_family.py "$PWD/Research/NativeFamily"
python Core/native/official_preview_prepare.py "$PWD/Research/NativeOfficial/upstream-fm1-open"
python Core/native/skiff_prepare.py "$PWD/Research/NativeSkiff/upstream-skiff"
python Core/native/expansion_prepare.py "$PWD/Research/NativeExpansion"
```

Source tags are checked against exact commits; notice/input/resource hashes
are verified. Existing checkouts are never reset. Release packages require their
exact size and SHA256. Existing mismatching packages are kept and cause failure.
Downloaded source and derived resources retain upstream component terms.

The ignored cache layout is:

```text
Research/NativeSloop/upstream-sloop-2.7.1/
Research/NativeFamily/{felucca,x0x,melodee}/
Research/NativeFamily/{felucca-1.6.1,x0x-1.1.0,melodee-1.4.3}.fwsc
Research/NativeOfficial/upstream-fm1-open/
Research/NativeOfficial/FM1-Open-V13.fwsc
Research/NativeSkiff/upstream-skiff/
Research/NativeSkiff/skiff-1.3.2.fwsc
Research/NativeExpansion/{fomni,jangada}/
Research/NativeExpansion/{fomni-0.4,jangada-1.0.1}.fwsc
```

SLOOP's exact package lives in its pinned checkout's docs/firmware directory.
A local ignored Research symlink may reuse an existing prepared cache; no host
path or symlink belongs in Git. The source/notice/resource checks still apply.

## Compile, sign and install

```sh
./Android/build.sh --core
adb install -r Android/build/FM1-android-arm64.apk
```

The --core option prepares the Rust static archive and all eight native engines.
Later Java/JNI-only iterations may omit --core to reuse that archive. APK assets
are checked against packages.lock.json before packaging. Native ELF segments
use 16 KiB alignment. This is an arm64-only APK with minSdk 26 and targetSdk 35.

Android/signing.sh creates an ignored local development key and a random local
password file, both mode 0600. Neither is published. Preserve both for updates;
a new clone creates a different signer. The signed output, intermediate sources,
archives and all package assets remain under ignored build/cache directories.

## Tests

Use a dedicated emulator with synthetic test projects. Instrumentation mutates
that app's test state and private project library; do not point it at a phone
containing unrelated owner work.

```sh
./Android/test.sh
```

The app must already be built/installed. The script wakes the dedicated emulator
and dismisses its keyguard before injecting real touch events. Default tests need only the exact
engine package assets already in the app and exercise ten core/UI cases. The
receipt explicitly records that external exchange fixtures were not supplied.
Optional cross-platform testing uses a caller-provided directory containing
swift-generated.fm1project, swift-generated.fm1backup and ios-simulator.fm1project:

```sh
FM1_EXCHANGE_FIXTURES=/path/to/local/synthetic-fixtures ./Android/test.sh
```

Those fixtures are never part of the public repository or consumer APK.
The test package shares the app's local signer and uses standard Android SDK
Instrumentation. The script exits nonzero if its final JSON receipt is absent
or reports failures.

Host Rust regression may also be run with all five FM1_NATIVE_* environment
variables from Android/build.sh pointing at the prepared cache:

```sh
cargo test --workspace --release
```

Without those environment variables, native-only test cases are conditional and
are not evidence for eight-engine compatibility.
