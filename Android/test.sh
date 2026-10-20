#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TOOLS="$SDK/build-tools/35.0.0"
OUT="$PWD/Android/build/test"
rm -rf "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/dex"
cat > "$OUT/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.fstack.fm1.tests"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="FM1 local validation"/><instrumentation android:name="ai.fstack.fm1.EngineInstrumentation" android:targetPackage="ai.fstack.fm1" android:functionalTest="true"/></manifest>
XML
rm -rf "$OUT/assets"
mkdir -p "$OUT/assets"
EXCHANGE=false
if [[ -n "${FM1_EXCHANGE_FIXTURES:-}" ]]; then
 mkdir -p "$OUT/assets/exchange"
 for name in swift-generated.fm1project swift-generated.fm1backup ios-simulator.fm1project; do
  cp "$FM1_EXCHANGE_FIXTURES/$name" "$OUT/assets/exchange/$name"
 done
 EXCHANGE=true
fi
"$TOOLS/aapt2" link -A "$OUT/assets" -I "$SDK/platforms/android-35/android.jar" --manifest "$OUT/AndroidManifest.xml" -o "$OUT/base.apk"
find Android/app/src/androidTest -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -cp "$SDK/platforms/android-35/android.jar:$PWD/Android/build/classes" -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$TOOLS/d8" --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --classpath "$PWD/Android/build/classes" --output "$OUT/dex" @"$OUT/classes.txt"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$PWD/Android/signing.sh" "$OUT/aligned.apk" "$OUT/tests.apk"
"$SDK/platform-tools/adb" install -r "$OUT/tests.apk"
"$SDK/platform-tools/adb" shell am instrument -w -e exchange "$EXCHANGE" ai.fstack.fm1.tests/ai.fstack.fm1.EngineInstrumentation | tee "$OUT/result.txt"
python3 - "$OUT/result.txt" <<'PYRESULT'
import json,pathlib,re,sys
text=pathlib.Path(sys.argv[1]).read_text();matches=list(re.finditer(r'\{\s*"platform"\s*:',text))
if not matches:sys.exit("No completed instrumentation receipt")
receipt=json.JSONDecoder().raw_decode(text[matches[-1].start():])[0]
if receipt.get("failures")!=0:sys.exit("Android instrumentation failed")
PYRESULT
