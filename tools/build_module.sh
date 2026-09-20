#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk_path=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}
build_tools="$sdk_path/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
if [ -n "${JAVA_HOME:-}" ]; then
 java_bin="$JAVA_HOME/bin"
elif [ -x /opt/homebrew/opt/openjdk/bin/javac ]; then
 java_bin=/opt/homebrew/opt/openjdk/bin
else
 java_bin=$(dirname "$(command -v javac)")
fi
xposed_api=${XPOSED_API:-}
if [ -z "$xposed_api" ] && [ -d "$HOME/.gradle/caches/modules-2/files-2.1/de.robv.android.xposed/api/82" ]; then
 xposed_api=$(find "$HOME/.gradle/caches/modules-2/files-2.1/de.robv.android.xposed/api/82" -name api-82.jar -print | head -n 1)
fi
if [ ! -f "$xposed_api" ]; then
 printf '%s\n' 'Set XPOSED_API to the local Xposed API 82 jar.' >&2
 exit 1
fi
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$project_dir/module/AndroidManifest.xml")
case "$version" in ''|*[!a-zA-Z0-9.-]*) printf '%s\n' 'Invalid APK version' >&2; exit 1;; esac
out="$project_dir/build/windowdeck"
mkdir -p "$out/classes" "$out/dex" "$out/gen"
"$build_tools/aapt2" compile --dir "$project_dir/module/res" -o "$out/resources.zip"
"$build_tools/aapt2" link -o "$out/base.apk" -I "$sdk_path/platforms/android-35/android.jar" --manifest "$project_dir/module/AndroidManifest.xml" --java "$out/gen" -A "$project_dir/module/assets" "$out/resources.zip"
find "$project_dir/module/src" "$out/gen" -name '*.java' > "$out/sources.list"
"$java_bin/javac" --release 8 -Xlint:-options -classpath "$sdk_path/platforms/android-35/android.jar:$xposed_api" -d "$out/classes" @"$out/sources.list"
"$java_bin/jar" cf "$out/classes.jar" -C "$out/classes" .
"$build_tools/d8" --min-api 35 --lib "$sdk_path/platforms/android-35/android.jar" --classpath "$xposed_api" --output "$out/dex" "$out/classes.jar"
cp "$out/base.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$build_tools/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
if [ ! -f "$project_dir/build/windowdeck-test.keystore" ]; then
 "$java_bin/keytool" -genkeypair -keystore "$project_dir/build/windowdeck-test.keystore" -alias windowdeck -storepass android -keypass android -dname 'CN=WindowDeck Development' -keyalg RSA -keysize 2048 -validity 3650
fi
"$build_tools/apksigner" sign --ks "$project_dir/build/windowdeck-test.keystore" --ks-pass pass:android --key-pass pass:android --out "$out/windowdeck-v$version.apk" "$out/aligned.apk"
"$build_tools/apksigner" verify "$out/windowdeck-v$version.apk"
printf '%s\n' "$out/windowdeck-v$version.apk"
