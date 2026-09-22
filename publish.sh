#!/usr/bin/env bash
#
# 一键发布 / 更新到 GitHub
#
#   ./publish.sh                     # 提交当前改动并推送（仓库不存在时自动创建）
#   ./publish.sh -m "修复了xxx"       # 自定义提交信息
#   ./publish.sh --private           # 首次创建时用私有仓库
#   ./publish.sh --release 1.0.0     # 推送后再打 tag，并把 APK 上传到 Release
#   ./publish.sh --dry-run           # 只打印将要执行的命令
#
# 一次性准备（二选一）：
#   A) 安装 gh 并登录：   brew install gh && gh auth login
#   B) 建一个 Personal Access Token（勾 repo 权限）后存到本地：
#        mkdir -p ~/.config/taitoulv && \
#        printf '%s' '你的token' > ~/.config/taitoulv/token && \
#        chmod 600 ~/.config/taitoulv/token
#      或设环境变量 GITHUB_TOKEN
#
# 可用环境变量覆盖：OWNER / REPO_NAME / BRANCH / DESCRIPTION / TOKEN_FILE
#
set -euo pipefail

OWNER="${OWNER:-pdiscat}"
REPO_NAME="${REPO_NAME:-TaiTouLv}"
BRANCH="${BRANCH:-main}"
DESCRIPTION="${DESCRIPTION:-抬头率检测：用手机摄像头实时统计课堂抬头率/趴桌率的 Android 应用（离线 ML Kit 人脸检测 + 行为事件统计 + CSV 记录与曲线）}"
TOKEN_FILE="${TOKEN_FILE:-$HOME/.config/taitoulv/token}"
APK_PATH="${APK_PATH:-app/build/outputs/apk/debug/app-debug.apk}"

COMMIT_MSG=""
PRIVATE=0
RELEASE_TAG=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -m|--message) COMMIT_MSG="${2:?缺少提交信息}"; shift 2 ;;
    --private)    PRIVATE=1; shift ;;
    --release)    RELEASE_TAG="${2:?缺少版本号，例如 --release 1.0.0}"; shift 2 ;;
    --dry-run)    DRY_RUN=1; shift ;;
    -h|--help)    sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（用 --help 看用法）"; exit 1 ;;
  esac
done

cd "$(dirname "$0")"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }
run()  { if [[ $DRY_RUN -eq 1 ]]; then echo "    (dry-run) $*"; else "$@"; fi; }

# ---------------------------------------------------------------- 凭据
TOKEN="${GITHUB_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$TOKEN_FILE" ]]; then
  TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_FILE")"
fi

GH_OK=0
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then GH_OK=1; fi

if [[ -z "$TOKEN" && $GH_OK -eq 0 && $DRY_RUN -eq 0 ]]; then
  die "没有可用凭据，二选一：
  A) brew install gh && gh auth login
  B) 在 GitHub 建 Personal Access Token（勾 repo 权限）后执行：
       mkdir -p ~/.config/taitoulv && printf '%s' '你的token' > ~/.config/taitoulv/token && chmod 600 ~/.config/taitoulv/token"
fi
if [[ $GH_OK -eq 1 ]]; then say "使用 gh CLI 认证"
elif [[ -n "$TOKEN" ]]; then say "使用 Token 认证（$TOKEN_FILE）"
else warn "未检测到凭据（dry-run 模式，仅演示流程）"; fi

# ---------------------------------------------------------------- 本地仓库
say "本地仓库：$(pwd)"
[[ -d .git ]] || run git init -b "$BRANCH"
git config user.name  >/dev/null || run git config user.name "$OWNER"
git config user.email >/dev/null || run git config user.email "${OWNER}@users.noreply.github.com"

# 别把不该进仓库的东西推上去
if git ls-files --error-unmatch local.properties >/dev/null 2>&1; then
  die "local.properties 被 git 跟踪了，先执行：git rm --cached local.properties"
fi
tracked_apk="$(git ls-files | grep -E '\.(apk|aab|jks|keystore)$' || true)"
[[ -z "$tracked_apk" ]] || die "有构建产物/密钥被跟踪，先移除：$tracked_apk"

# ---------------------------------------------------------------- 提交
if [[ -z "$(git status --porcelain)" ]]; then
  say "工作区干净，没有需要提交的改动"
else
  say "提交改动"
  run git add -A
  run git commit -m "${COMMIT_MSG:-更新 $(date '+%Y-%m-%d %H:%M')}"
fi

# ---------------------------------------------------------------- 远程仓库
repo_exists() {
  if [[ $GH_OK -eq 1 ]]; then
    gh repo view "${OWNER}/${REPO_NAME}" >/dev/null 2>&1
  else
    [[ "$(curl -s -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer $TOKEN" \
        "https://api.github.com/repos/${OWNER}/${REPO_NAME}")" == "200" ]]
  fi
}

create_repo() {
  say "在 GitHub 创建仓库 ${OWNER}/${REPO_NAME}"
  if [[ $GH_OK -eq 1 ]]; then
    vis="--public"; [[ $PRIVATE -eq 1 ]] && vis="--private"
    run gh repo create "${OWNER}/${REPO_NAME}" "$vis" --description "$DESCRIPTION"
  else
    priv=false; [[ $PRIVATE -eq 1 ]] && priv=true
    run curl -sS -X POST \
      -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
      https://api.github.com/user/repos \
      -d "{\"name\":\"${REPO_NAME}\",\"private\":${priv},\"description\":\"${DESCRIPTION}\",\"has_issues\":true,\"has_wiki\":false}"
    echo
  fi
}

if repo_exists; then
  say "远程仓库已存在"
else
  create_repo
fi

REMOTE_URL="https://github.com/${OWNER}/${REPO_NAME}.git"
if git remote get-url origin >/dev/null 2>&1; then
  run git remote set-url origin "$REMOTE_URL"
else
  run git remote add origin "$REMOTE_URL"
fi

# ---------------------------------------------------------------- 推送
say "推送到 origin/$BRANCH"
if [[ $GH_OK -eq 1 ]]; then
  run git push -u origin "$BRANCH"
else
  # 用带 token 的临时 URL 推送，避免把 token 写进 .git/config
  run git push "https://x-access-token:${TOKEN}@github.com/${OWNER}/${REPO_NAME}.git" "HEAD:refs/heads/${BRANCH}"
  run git fetch origin "$BRANCH" || true
  run git branch --set-upstream-to="origin/${BRANCH}" "$BRANCH" || true
fi

# ---------------------------------------------------------------- 可选：Release + APK
if [[ -n "$RELEASE_TAG" ]]; then
  tag="v${RELEASE_TAG#v}"
  if [[ ! -f "$APK_PATH" ]]; then
    warn "找不到 $APK_PATH，先跑 ./gradlew assembleDebug 再来发 Release"
  else
    say "打 tag $tag 并推送"
    if git rev-parse "$tag" >/dev/null 2>&1; then
      warn "tag $tag 已存在，跳过创建"
    else
      run git tag -a "$tag" -m "Release $tag"
    fi
    if [[ $GH_OK -eq 1 ]]; then
      run git push origin "$tag"
      say "创建 Release 并上传 APK"
      run gh release create "$tag" "$APK_PATH" --title "$tag" --notes "发布 $tag（$(date '+%Y-%m-%d %H:%M')）"
    else
      run git push "https://x-access-token:${TOKEN}@github.com/${OWNER}/${REPO_NAME}.git" "$tag"
      api="https://api.github.com/repos/${OWNER}/${REPO_NAME}"
      say "创建 Release"
      rel="$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
            "$api/releases" -d "{\"tag_name\":\"$tag\",\"name\":\"$tag\",\"body\":\"发布 $tag\"}")"
      up="$(printf '%s' "$rel" | python3 -c "import json,sys;print(json.load(sys.stdin).get('upload_url','').split('{')[0])")"
      [[ -n "$up" ]] || die "创建 Release 失败：$rel"
      say "上传 APK（约 48MB，请稍等）"
      run curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/vnd.android.package-archive" \
        --data-binary "@$APK_PATH" "${up}?name=app-debug.apk"
      echo
    fi
  fi
fi

say "完成 → https://github.com/${OWNER}/${REPO_NAME}"
