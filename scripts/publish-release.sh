#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ClipDown 一键发布脚本
#
# 做四件事：
#   1. 自增 version.properties 里的 versionCode / versionName（versionCode 单调递增是更新器的比较依据）
#   2. 构建正式签名 release APK
#   3. 生成 update.json（应用内更新清单：版本号 / 下载地址 / 说明 / sha256 / 体积）
#   4. 用 gh 创建 GitHub Release，上传 APK 与 update.json
#
# 用法：
#   bash scripts/publish-release.sh                      # 补丁位自增（1.0.1 → 1.0.2）
#   bash scripts/publish-release.sh --bump minor         # 次版本自增（1.0.2 → 1.1.0）
#   bash scripts/publish-release.sh --bump major         # 主版本自增
#   bash scripts/publish-release.sh --no-bump            # 不改版本号，重发当前版本
#   bash scripts/publish-release.sh --notes "修复了…"     # 指定更新说明（多行用 \n）
#   bash scripts/publish-release.sh --repo me/clipdown-dist
#   bash scripts/publish-release.sh --dry-run            # 只构建+生成清单，不推送
#   bash scripts/publish-release.sh --create-repo        # 目标仓库不存在时自动创建（公开）
#
# 注意：**发布仓库必须是公开仓库**，否则应用侧无法匿名下载 Release 资产。
#       源码仓库可以继续私有 —— APK 与 update.json 发到公开分发仓库即可。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ── 参数 ─────────────────────────────────────────────────────────────────────
BUMP="patch"
NOTES=""
DRY_RUN=0
CREATE_REPO=0
SKIP_BUILD=0
REPO=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bump)        BUMP="${2:-patch}"; shift 2 ;;
    --no-bump)     BUMP="none"; shift ;;
    --notes)       NOTES="${2:-}"; shift 2 ;;
    --repo)        REPO="${2:-}"; shift 2 ;;
    --dry-run)     DRY_RUN=1; shift ;;
    --create-repo) CREATE_REPO=1; shift ;;
    --skip-build)  SKIP_BUILD=1; shift ;;
    -h|--help)     sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

# 默认发布仓库取自 gradle.properties，保证与 App 内更新通道一致
if [[ -z "$REPO" ]]; then
  REPO="$(grep -E '^clipdown\.updateRepo=' gradle.properties | cut -d= -f2- | tr -d '\r')"
fi
APK_ASSET="$(grep -E '^clipdown\.apkAssetName=' gradle.properties | cut -d= -f2- | tr -d '\r')"
APK_ASSET="${APK_ASSET:-clipdown-release.apk}"
[[ -z "$REPO" ]] && { echo "未指定发布仓库：用 --repo owner/name 或设置 gradle.properties 的 clipdown.updateRepo" >&2; exit 2; }

echo "▶ 发布仓库：$REPO"
echo "▶ APK 资产名：$APK_ASSET"

# ── 1. 版本号自增 ────────────────────────────────────────────────────────────
VP="version.properties"
OLD_CODE="$(grep -E '^versionCode=' "$VP" | cut -d= -f2 | tr -d '\r')"
OLD_NAME="$(grep -E '^versionName=' "$VP" | cut -d= -f2 | tr -d '\r')"

NEW_CODE="$OLD_CODE"
NEW_NAME="$OLD_NAME"
if [[ "$BUMP" != "none" ]]; then
  IFS='.' read -r MA MI PA <<< "$OLD_NAME"
  MA="${MA:-1}"; MI="${MI:-0}"; PA="${PA:-0}"
  case "$BUMP" in
    patch) PA=$((PA + 1)) ;;
    minor) MI=$((MI + 1)); PA=0 ;;
    major) MA=$((MA + 1)); MI=0; PA=0 ;;
    *) echo "--bump 只支持 patch|minor|major|none，收到：$BUMP" >&2; exit 2 ;;
  esac
  NEW_NAME="${MA}.${MI}.${PA}"
  NEW_CODE=$((OLD_CODE + 1))
fi

echo "▶ 版本：${OLD_NAME}(${OLD_CODE})  →  ${NEW_NAME}(${NEW_CODE})"

if [[ "$DRY_RUN" == "0" && "$BUMP" != "none" ]]; then
  # version.properties 用 sed 原地改（保持文件里其余注释不动）
  sed -i "s/^versionCode=.*/versionCode=${NEW_CODE}/" "$VP"
  sed -i "s/^versionName=.*/versionName=${NEW_NAME}/" "$VP"
  echo "  已更新 $VP"
fi

# ── 2. 构建 release APK ──────────────────────────────────────────────────────
APK_SRC="app/build/outputs/apk/release/app-release.apk"
if [[ "$SKIP_BUILD" == "0" ]]; then
  echo "▶ 构建 release APK（assembleRelease + 单测）…"
  export JAVA_HOME="${JAVA_HOME:-F:\\AndroidDev\\jdk\\jdk-17.0.20.1+1}"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-F:\\AndroidDev\\.gradle}"
  export ANDROID_HOME="${ANDROID_HOME:-F:\\AndroidDev\\sdk}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-F:\\AndroidDev\\sdk}"
  GRADLE_BIN="${GRADLE_BIN:-/f/AndroidDev/gradle-8.9/bin/gradle.bat}"
  "$GRADLE_BIN" -p "$ROOT" --no-daemon \
    -Dorg.gradle.java.home="$JAVA_HOME" \
    :app:assembleRelease :parser:test :downloader:testDebugUnitTest :app:testDebugUnitTest \
    --console=plain
else
  echo "▶ 跳过构建（--skip-build），复用已有 APK"
fi

[[ -f "$APK_SRC" ]] || { echo "找不到 APK：$APK_SRC" >&2; exit 1; }

# ── 3. 生成 update.json ─────────────────────────────────────────────────────
DIST="build/dist"
mkdir -p "$DIST"
cp "$APK_SRC" "$DIST/$APK_ASSET"

APK_URL="https://github.com/${REPO}/releases/latest/download/${APK_ASSET}"
APK_SIZE="$(stat -c %s "$DIST/$APK_ASSET" 2>/dev/null || wc -c < "$DIST/$APK_ASSET" | tr -d ' ')"
APK_SHA="$(sha256sum "$DIST/$APK_ASSET" | cut -d' ' -f1)"

# 说明：notes 里的换行转义成 \n，保证是合法 JSON
NOTES_JSON="$(printf '%s' "$NOTES" | sed ':a;N;$!ba;s/\n/\\n/g' | sed 's/"/\\"/g')"

cat > "$DIST/update.json" <<JSON
{
  "versionCode": ${NEW_CODE},
  "versionName": "${NEW_NAME}",
  "notes": "${NOTES_JSON}",
  "apkUrl": "${APK_URL}",
  "sha256": "${APK_SHA}",
  "sizeBytes": ${APK_SIZE},
  "mandatory": false
}
JSON

echo "▶ 已生成："
echo "  $DIST/$APK_ASSET   ($(( APK_SIZE / 1024 )) KB, sha256=${APK_SHA:0:12}…)"
echo "  $DIST/update.json"
cat "$DIST/update.json" | sed 's/^/    /'

if [[ "$DRY_RUN" == "1" ]]; then
  echo "▶ --dry-run：已跳过 gh 发布。"
  exit 0
fi

# ── 4. 发布到 GitHub Release ────────────────────────────────────────────────
if ! gh repo view "$REPO" >/dev/null 2>&1; then
  if [[ "$CREATE_REPO" == "1" ]]; then
    echo "▶ 目标仓库不存在，创建公开仓库 $REPO …"
    gh repo create "$REPO" --public \
      --description "ClipDown 测试版分发仓库（仅存放 APK 与更新清单）"
    # 分发仓库需要一个初始提交，否则 release 的 tag 无基点
    TMP="$(mktemp -d)"
    ( cd "$TMP" && git init -q && echo "# ClipDown 分发仓库" > README.md \
      && git add README.md && git -c user.email=release@local -c user.name=release commit -qm init \
      && git branch -M main && git remote add origin "https://github.com/${REPO}.git" && git push -q -u origin main )
    rm -rf "$TMP"
  else
    echo "目标仓库 $REPO 不存在。" >&2
    echo "  ① 公开分发仓库（推荐，源码仓库保持私有）：重跑并加 --create-repo" >&2
    echo "  ② 或先手动创建：gh repo create $REPO --public" >&2
    echo "  ③ 若坚持发到私有仓库，应用侧将无法匿名下载（需要另配 token）" >&2
    exit 1
  fi
fi

TAG="v${NEW_NAME}"
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "标签 $TAG 已存在，改为覆盖上传资产…" >&2
  gh release upload "$TAG" --repo "$REPO" --clobber \
    "$DIST/$APK_ASSET" "$DIST/update.json"
else
  gh release create "$TAG" --repo "$REPO" \
    --title "ClipDown ${NEW_NAME}（${NEW_CODE}）" \
    --notes "${NOTES:-测试版 ${NEW_NAME}}" \
    --latest \
    "$DIST/$APK_ASSET" "$DIST/update.json"
fi

echo
echo "✅ 发布完成：https://github.com/${REPO}/releases/tag/${TAG}"
echo "   应用内更新清单：https://github.com/${REPO}/releases/latest/download/update.json"
echo "   直接下载 APK ：${APK_URL}"
