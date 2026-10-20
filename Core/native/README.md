# Pinned source-native engines

These GPL adapters and state-isolation tools compile eight exact public firmware
source versions. No proprietary FM1 SDK archive is linked. Source checkouts,
firmware resources, packages and build receipts stay in ignored local paths.

See the root README and docs/build.md for the full preparation/build commands.
Each *.lock.json retains public source commits, upstream resource hashes,
matching package metadata and original notice hashes. packages.lock.json adds
all eight package cache paths and exact download checks for the Android build.
Pin/resource/compiler mismatches stop the build without a fallback.

The wrappers derive from the Virtual FM-1 commit pinned in upstream.lock.json.
Mutable firmware state is isolated per instance before optimization. The owning
host worker serializes every call; the audio output thread consumes only PCM.
SLOOP's raw store is 1 MiB; FM1 Open portable preview owns a separate 536576-byte
logical store. Preview data never seeds another engine's flash.

Original source/component notices are retained under licenses/ and the root
licenses/ directory. Existing Apple target support belongs to the shared native
build tooling; this repository contains no iOS frontend or Xcode project.
