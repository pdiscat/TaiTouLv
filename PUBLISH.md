# 发布到 GitHub

一键脚本 `./publish.sh`：**提交 → 建仓库 → 推送 →（可选）发 Release 并上传 APK**。

## 一次性准备（二选一）

### 方式 A：GitHub CLI（最省事）

```bash
brew install gh
gh auth login        # 选 GitHub.com → HTTPS → 用浏览器登录
```

### 方式 B：Personal Access Token（不想装 gh）

1. 打开 <https://github.com/settings/tokens> 生成一个 **Classic** token，勾选 **repo** 权限
   （或 Fine-grained token，给目标仓库 **Contents: Read and write**）；
2. 存到本地文件（脚本只读它，不会外传、也不会写进 git 配置）：

```bash
mkdir -p ~/.config/taitoulv
printf '%s' '你的token' > ~/.config/taitoulv/token
chmod 600 ~/.config/taitoulv/token
```

## 常用命令

```bash
./publish.sh                    # 提交当前改动并推送（仓库不存在会自动创建）
./publish.sh -m "新增分组统计"    # 自定义提交信息
./publish.sh --private          # 首次创建成私有仓库
./publish.sh --release 1.0.0    # 推送 + 打 tag v1.0.0 + 把 APK 传到 Release
./publish.sh --dry-run          # 只打印将要执行的操作，不实际执行
./publish.sh --help             # 用法
```

## 脚本做了什么

1. 首次运行 `git init -b main`，并把提交身份设成 `pdiscat` + `pdiscat@users.noreply.github.com`
   （**只写进本仓库**，不动你的全局 git 配置）；
2. 安全检查：拒绝提交 `local.properties`、`*.apk`、`*.aab`、`*.jks`、`*.keystore` 这类不该进仓库的东西；
3. `git add -A` + 提交（默认提交信息带时间戳）；
4. 远程仓库不存在就创建（有 gh 用 `gh repo create`，否则用 GitHub API），然后 `git push -u origin main`；
   - 用 Token 时推送走临时的 `https://x-access-token:…@github.com/…` URL，**不会把 token 写进 `.git/config`**；
5. 带 `--release` 时：打 tag → 建 Release → 把 `app/build/outputs/apk/debug/app-debug.apk`
   作为附件上传（所以仓库本身不用塞 48MB 的 APK，用户从 Releases 下载安装包）。

## 改账号 / 仓库名 / 分支

用环境变量覆盖即可：

```bash
REPO_NAME=TaiTouLv-Android ./publish.sh
OWNER=someoneelse BRANCH=master ./publish.sh
DESCRIPTION="一句话简介" ./publish.sh
```

## 仓库里都放了什么

- 源码：`app/src/main/java/com/example/taitoulv/`（`MainActivity` / `FaceAnalyzer` / `AttentionTracker` / `CsvRecorder` / `CsvRepository` / `TrendChartView` / `RecordsActivity` / `HeadUpOverlayView`）
- 单元测试：`app/src/test/java/com/example/taitoulv/`（14 个用例，`./gradlew testDebugUnitTest`）
- 资源与布局：`app/src/main/res/`（含 `layout-land` 横屏布局）
- 文档：`README.md`（判定原理、坐标映射踩坑、闪退排查、机型实测、课堂部署建议）、`LICENSE`(MIT)、本文件
- `docs/screenshot-chart.png`：数据页截图（README 里引用）

不进仓库：`build/`、`.gradle/`、`.idea/`、`local.properties`、`*.apk`、密钥文件。
