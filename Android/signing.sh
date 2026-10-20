#!/bin/bash
# Local development signing only. Neither key nor password is tracked.
set -euo pipefail
cd "$(dirname "$0")/.."
KEY="$PWD/Android/local-signing.jks"
PASSWORD="$PWD/Android/.signing-password"
if [[ ! -f "$KEY" ]]; then
 python3 - "$PASSWORD" <<'PY'
from pathlib import Path
import secrets,sys
path=Path(sys.argv[1]);path.write_text(secrets.token_hex(32)+'\n');path.chmod(0o600)
PY
 keytool -genkeypair -keystore "$KEY" -storepass:file "$PASSWORD" -keypass:file "$PASSWORD" -alias fm1 -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=FM1 Local Development' >/dev/null
 chmod 600 "$KEY"
fi
if [[ ! -f "$PASSWORD" ]]; then
 echo 'Local signing password is missing; restore the matching local file.' >&2
 exit 1
fi
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
"$SDK/build-tools/35.0.0/apksigner" sign --ks "$KEY" --ks-pass "file:$PASSWORD" --out "$2" "$1"
