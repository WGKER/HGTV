#!/usr/bin/env bash
# 版本号 / 版本 code / 应用名 / 产物名的唯一读取入口。
#
# 真值在 android/app/build.gradle.kts（可用 HGTV_VERSION_NAME / HGTV_VERSION_CODE 覆盖）。
# 本地构建脚本与 GitHub Actions 都从这里读，避免两边各写一套 sed 之后对不上。
#
# 用法：
#   tools/version.sh name     # 红果TV
#   tools/version.sh version  # 0.15.2
#   tools/version.sh code     # 23
#   tools/version.sh apk      # 红果TV_0.15.2_23.apk
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# HGTV_GRADLE_FILE 可指向别处的同名文件（CI 里用它读上一个提交的版本做对比）
gradle_file="${HGTV_GRADLE_FILE:-$repo_dir/android/app/build.gradle.kts}"

name="红果TV"
# 环境变量前缀不写死：历史提交里是 HONGGUOTV_*，现在是 HGTV_*，两种都能读
version="${HGTV_VERSION_NAME:-$(sed -n 's/.*versionName = System.getenv("[A-Z_]*") ?: "\([^"]*\)".*/\1/p' "$gradle_file" | head -1)}"
code="${HGTV_VERSION_CODE:-$(sed -n 's/.*versionCode = System.getenv("[A-Z_]*")?\.toInt() ?: \([0-9]*\).*/\1/p' "$gradle_file" | head -1)}"

[[ -n "$version" && -n "$code" ]] || { echo "读不到版本号：$gradle_file" >&2; exit 1; }

case "${1:-apk}" in
  name)    printf '%s\n' "$name" ;;
  version) printf '%s\n' "$version" ;;
  code)    printf '%s\n' "$code" ;;
  apk)     printf '%s_%s_%s.apk\n' "$name" "$version" "$code" ;;
  *) echo "用法：tools/version.sh [name|version|code|apk]" >&2; exit 2 ;;
esac
