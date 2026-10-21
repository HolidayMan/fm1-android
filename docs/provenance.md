# Source and asset provenance

The Android Kotlin/Canvas host and JNI glue are project-owned code. Shared Rust
C-ABI adapters and GPL source-native tooling were extracted from the private
FM-1 iOS development tree without its Git history, Swift frontend, device data,
credentials, logs or firmware-derived generated sources.

The reference emulator crates retain their original MIT attribution and pinned
upstream revision in Vendor/UPSTREAM.md. Native wrapper/state-tool origin is
Virtual FM-1 commit c52668953231655046ee142d2b3ab4fd2c8ca0b4. Their original
copyright/license comments and notices are retained.

| Native source | Public repository | Exact commit |
| --- | --- | --- |
| SLOOP | https://github.com/isod89/sloop-fm1 | `b77bf55e3bf804665b8f5dffb5955bd8443311f0` |
| felucca | https://github.com/hugelton/Felucca | `a53ecf428c29c780108fd70a31b71a32a6b5c313` |
| x0x | https://github.com/charlesvestal/fm1-x0x | `27f6fac7e07ce97b5de158b45aec99f59a9e23fd` |
| melodee | https://github.com/keremimo/melodee | `398ada1e09d864c12f3b778a43e45c7c2a8031b8` |
| FM1 Open | https://github.com/m-vave/fm1_open | `4d266850f8a46bd5154a0d495c1f60317156c9f7` |
| Skiff | https://github.com/illuminus5/skiff | `c01fa1ed619266d770ea6552b153612e1b77d0a9` |
| fomni | https://github.com/charlesvestal/fm1-fomni | `6ecd227013cc18def0e89d035b3760eb268b342b` |
| jangada | https://github.com/zednaked/jangada | `46e6c2c2ed94b609918aa6f5f7381fac915ffc45` |

All upstream checkouts, resource-generator output and exact release packages
are prepared into ignored local storage. No external firmware source/assets,
factory sound banks, opaque SDK archive, executable or private save is committed.
The generated_isa.rs file in Vendor is the required MIT upstream Rust ISA source;
it is distinct from excluded firmware-derived generated code.

The FM1 Open adapter builds the public portable preview source, excluding board
hardware drivers and the proprietary sdk/lib/libfm1_engine.a. Its independent
logical store is 536576 bytes; the other seven engines own 1 MiB stores.

The Android icon at Android/app/src/main/res/drawable/icon.png is a 192×192 resize
of an existing generated original app icon. The original design study used the
manufacturer's product photograph as a visual reference:
https://www.m-vave.com/images/productInfo/fm-1-3.webp . That photograph is not
packaged as the icon. No generation prompt is included.

The README screenshot is the actual Android native panel in synthetic test
state on an emulator. It contains no owner project, personal saved data or
private recording. Engine LCD and UI graphics retain their upstream attribution.

The 0.3.0 audit retains the newly checked upstream notice files under
`Core/native/licenses/` and includes all of them in the APK's About notices.
Melodee's Casio/Sequential factory data explicitly has no redistribution license
granted by upstream; the lock and original notices retain that limitation.
Skiff's YuE2 BREAK/FUNK provenance remains separate from its GPL source license.
FM1 Open V14 still builds the synthesized-demo portable source bank; the matching
hardware package identifies the admitted release and does not supply its opaque
SDK or factory bank to the native preview.
