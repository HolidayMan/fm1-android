#!/bin/bash
# Exact compiler/bootstrap cache; only Kotlin stdlib is shipped at runtime.
set -euo pipefail
FM1_KOTLIN_VERSION=2.0.21
FM1_KOTLIN_SHA256=0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a
FM1_KOTLIN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.tools/kotlin"
FM1_KOTLIN_ZIP="$FM1_KOTLIN_ROOT/compiler-$FM1_KOTLIN_VERSION.zip"
KOTLIN_HOME="$FM1_KOTLIN_ROOT/$FM1_KOTLIN_VERSION/kotlinc"
mkdir -p "$FM1_KOTLIN_ROOT"
if [[ ! -f "$FM1_KOTLIN_ZIP" ]]; then
 curl -L --fail --silent --show-error "https://github.com/JetBrains/kotlin/releases/download/v$FM1_KOTLIN_VERSION/kotlin-compiler-$FM1_KOTLIN_VERSION.zip" -o "$FM1_KOTLIN_ZIP.download"
 mv "$FM1_KOTLIN_ZIP.download" "$FM1_KOTLIN_ZIP"
fi
python3 - "$FM1_KOTLIN_ZIP" "$FM1_KOTLIN_SHA256" <<'PY'
import hashlib,pathlib,sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()!=sys.argv[2]:
 sys.exit('Kotlin compiler checksum differs; cache kept without using it')
PY
if [[ ! -x "$KOTLIN_HOME/bin/kotlinc" ]]; then
 mkdir -p "$FM1_KOTLIN_ROOT/$FM1_KOTLIN_VERSION"
 unzip -q "$FM1_KOTLIN_ZIP" -d "$FM1_KOTLIN_ROOT/$FM1_KOTLIN_VERSION"
fi
export KOTLIN_HOME
