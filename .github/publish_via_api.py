#!/usr/bin/env python3
"""
通过 GitHub REST API 把当前仓库内容提交上去（走 api.github.com）。

用途：有些网络环境下 `git push`（github.com 的 git 协议端点）会一直 408/超时，
但 api.github.com 是通的。这时用这个脚本可以照常发布/更新。

用法（一般由 publish.sh 自动调用）：
    OWNER=pdiscat REPO_NAME=TaiTouLv ./.github/publish_via_api.py
需要凭据：环境变量 GITHUB_TOKEN，或本机已登录的 gh（gh auth token）。
"""
import base64
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime

OWNER = os.environ.get("OWNER", "pdiscat")
REPO = os.environ.get("REPO_NAME", "TaiTouLv")
BRANCH = os.environ.get("BRANCH", "main")
MESSAGE = os.environ.get("COMMIT_MSG") or f"更新 {datetime.now():%Y-%m-%d %H:%M}"


def token() -> str:
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        return tok
    try:
        return subprocess.run(["gh", "auth", "token"], capture_output=True, text=True,
                              check=True).stdout.strip()
    except Exception:
        sys.exit("没有凭据：请设置 GITHUB_TOKEN，或先 gh auth login")


TOKEN = token()


def api(method: str, path: str, payload=None):
    """优先用 gh api（自带认证与代理处理，最稳），没有 gh 时退回 urllib"""
    if shutil.which("gh"):
        cmd = ["gh", "api", "-X", method, f"/repos/{OWNER}/{REPO}{path}"]
        if payload is not None:
            cmd += ["--input", "-"]
        proc = subprocess.run(cmd, input=json.dumps(payload) if payload is not None else None,
                              capture_output=True, text=True)
        if proc.returncode != 0:
            if "HTTP 404" in proc.stderr:
                raise urllib.error.HTTPError(path, 404, "Not Found", None, None)
            sys.exit(f"gh api {method} {path} 失败：{proc.stderr.strip()[:300]}")
        return json.loads(proc.stdout) if proc.stdout.strip() else {}

    url = f"https://api.github.com/repos/{OWNER}/{REPO}{path}"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, method=method, data=data, headers={
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "taitoulv-publish",
    })
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            body = resp.read().decode()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:300]
        if e.code == 404:
            raise
        sys.exit(f"API {method} {path} 失败 HTTP {e.code}: {detail}")


def main() -> int:
    tracked = subprocess.run(["git", "ls-files"], capture_output=True, text=True,
                             check=True).stdout.split()
    if not tracked:
        sys.exit("git ls-files 为空，先在仓库根目录执行")

    tree = []
    text_count = binary_count = 0
    for path in sorted(tracked):
        if not os.path.isfile(path):
            continue
        raw = open(path, "rb").read()
        try:
            tree.append({"path": path, "mode": "100644", "type": "blob",
                         "content": raw.decode("utf-8")})
            text_count += 1
        except UnicodeDecodeError:
            blob = api("POST", "/git/blobs", {
                "content": base64.b64encode(raw).decode(),
                "encoding": "base64",
            })
            tree.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
            binary_count += 1

    print(f"  文件 {len(tree)} 个（文本 {text_count} / 二进制 {binary_count}）")

    parents = []
    try:
        ref = api("GET", f"/git/ref/heads/{BRANCH}")
        parents = [ref["object"]["sha"]]
        print(f"  远端已有分支 {BRANCH}，在其之上追加提交")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        print(f"  远端还没有 {BRANCH}，创建首个提交")

    new_tree = api("POST", "/git/trees", {"tree": tree})
    commit = api("POST", "/git/commits", {
        "message": MESSAGE, "tree": new_tree["sha"], "parents": parents,
    })
    if parents:
        api("PATCH", f"/git/refs/heads/{BRANCH}", {"sha": commit["sha"], "force": False})
    else:
        api("POST", "/git/refs", {"ref": f"refs/heads/{BRANCH}", "sha": commit["sha"]})

    print(f"  提交成功 {commit['sha'][:10]}  https://github.com/{OWNER}/{REPO}/commit/{commit['sha']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
