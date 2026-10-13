#!/usr/bin/env bash
# 构建正式签名 APK。
#
# 产物命名：<应用名>_<版本号>.apk，例如 红果TV_0.15.2.apk
# 版本与名称从 tools/version.sh 读（真值在 android/app/build.gradle.kts）。
#
# 签名：android/keystore.properties（本机，不入库），或下列环境变量
#   HGTV_KEYSTORE / HGTV_STORE_PASSWORD / HGTV_KEY_ALIAS / HGTV_KEY_PASSWORD
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for native_jdk in "$HOME"/.cache/hgtv/toolchain/jdk-17*/Contents/Home; do
    if [[ -x "$native_jdk/bin/java" ]]; then export JAVA_HOME="$native_jdk"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.cache/hgtv/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.cache/hgtv/android-sdk"
fi

"$repo_dir/android/gradlew" -p "$repo_dir/android" :core:test :app:lintRelease :app:assembleRelease --console=plain

apk_name="$("$repo_dir/tools/version.sh" apk)"
mkdir -p "$repo_dir/outputs"
native_apk="$repo_dir/outputs/$apk_name"
cp "$repo_dir/android/app/build/outputs/apk/release/app-release.apk" "$native_apk"

echo
echo "产物：$native_apk"
if command -v shasum >/dev/null; then shasum -a 256 "$native_apk"; else sha256sum "$native_apk"; fi
