#!/usr/bin/env bash
# Builds build/arc-volume-<version>.apk without Gradle:
# aapt2 compile/link, javac, d8, aapt add, zipalign, apksigner.
# Runs on Linux, macOS and Windows (Git Bash).
#
# Usage: bash build.sh [version]      version defaults to the VERSION file, e.g. 1.2.3
#
# Signing: with ARC_KEYSTORE and ARC_KEYSTORE_PASS set, the APK is signed with that release key
# (alias ARC_KEY_ALIAS, default "arc-volume"). Otherwise a local debug.keystore is created and used.
set -euo pipefail

BUILD_TOOLS_VERSION=34.0.0
PLATFORM=android-34

cd "$(dirname "$0")"

version="${1:-$(tr -d '[:space:]' < VERSION)}"
if ! [[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "Version must look like 1.2.3, got: $version" >&2
    exit 1
fi
# 1.2.3 -> 10203; each part must stay below 100.
version_code=$((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        default_sdk="$(cygpath -u "$LOCALAPPDATA")/Android/Sdk"
        exe=.exe
        bat=.bat
        ;;
    Darwin)
        default_sdk="$HOME/Library/Android/sdk"
        exe=
        bat=
        ;;
    *)
        default_sdk="$HOME/Android/Sdk"
        exe=
        bat=
        ;;
esac

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$default_sdk}}"
build_tools="$sdk/build-tools/$BUILD_TOOLS_VERSION"
android_jar="$sdk/platforms/$PLATFORM/android.jar"

if [ ! -f "$android_jar" ] || [ ! -d "$build_tools" ]; then
    echo "Android SDK not found in $sdk" >&2
    echo "Set ANDROID_HOME and install \"build-tools;$BUILD_TOOLS_VERSION\" \"platforms;$PLATFORM\"." >&2
    exit 1
fi

rm -rf build
mkdir -p build/classes build/dex

"$build_tools/aapt2$exe" compile --dir res -o build/res.zip
"$build_tools/aapt2$exe" link -o build/app.unaligned.apk -I "$android_jar" \
    --manifest AndroidManifest.xml build/res.zip \
    --min-sdk-version 34 --target-sdk-version 34 \
    --version-code "$version_code" --version-name "$version"

# Relative paths in the argument file, so the Windows javac reads them too.
find src -name '*.java' > build/sources.txt
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$android_jar" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .

"$build_tools/d8$bat" --release --min-api 34 --lib "$android_jar" --output build/dex build/classes.jar

# aapt stores the path as given, so classes.dex must be added from inside its directory.
(cd build/dex && "$build_tools/aapt$exe" add ../app.unaligned.apk classes.dex)

"$build_tools/zipalign$exe" -p -f 4 build/app.unaligned.apk build/app.unsigned.apk

apk="build/arc-volume-$version.apk"
if [ -n "${ARC_KEYSTORE:-}" ]; then
    : "${ARC_KEYSTORE_PASS:?ARC_KEYSTORE_PASS must be set together with ARC_KEYSTORE}"
    "$build_tools/apksigner$bat" sign --ks "$ARC_KEYSTORE" --ks-pass env:ARC_KEYSTORE_PASS \
        --ks-key-alias "${ARC_KEY_ALIAS:-arc-volume}" --out "$apk" build/app.unsigned.apk
    signed_with="release key $ARC_KEYSTORE"
else
    if [ ! -f debug.keystore ]; then
        keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey \
            -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
    fi
    "$build_tools/apksigner$bat" sign --ks debug.keystore --ks-pass pass:android \
        --out "$apk" build/app.unsigned.apk
    signed_with="debug key (not for release)"
fi
"$build_tools/apksigner$bat" verify "$apk"

echo "Built $apk (version $version, code $version_code), signed with $signed_with"
