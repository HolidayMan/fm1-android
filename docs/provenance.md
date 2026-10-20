# Source and asset provenance

The Android Java/Canvas host and JNI glue are project-owned code. Shared Rust
C-ABI adapters and GPL source-native tooling were extracted from the private
FM-1 iOS development tree without its Git history, Swift frontend, device data,
credentials, logs or firmware-derived generated sources.

The reference emulator crates retain their original MIT attribution and pinned
upstream revision in Vendor/UPSTREAM.md. Native wrapper/state-tool origin is
Virtual FM-1 commit c52668953231655046ee142d2b3ab4fd2c8ca0b4. Their original
copyright/license comments and notices are retained.

| Native source | Public repository | Exact commit |
| --- | --- | --- |
| SLOOP | https://github.com/isod89/sloop-fm1 | `4ec1ac829fd2bdf2c571ea215fb3d62477d01220` |
| felucca | https://github.com/hugelton/Felucca | `a53ecf428c29c780108fd70a31b71a32a6b5c313` |
| x0x | https://github.com/charlesvestal/fm1-x0x | `27f6fac7e07ce97b5de158b45aec99f59a9e23fd` |
| melodee | https://github.com/keremimo/melodee | `ba9c812a603607012d68544b1acb6ccc8033eec5` |
| FM1 Open | https://github.com/m-vave/fm1_open | `b178ac43c25f010c60a878056fb5331fe693fd62` |
| Skiff | https://github.com/illuminus5/skiff | `c4bb2b4e9015cf6244f98db1d319874e15eb0767` |
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
