# 银杏助手开发与发布

公开仓库为 `https://github.com/luke-opus/yinxing-assistant`。本仓库包括 Android 应用、规则整理工具、官网和反馈服务源码；源码在仓库中不表示某个版本已签名、上线或完成真机验证。

## 目录

| 目录 | 内容 |
| --- | --- |
| `gkd-app/` | Android 应用；正式包名保持 `app.xadskip.android` |
| `web/main/` | 官网静态源码模板；直接打开时是禁用下载的发布预览 |
| `web/feedback/` | 反馈与许愿池页面、样式、图标与前端测试 |
| `services/feedback/` | Python 反馈与许愿池 API、测试及 Caddy/systemd 部署工具 |
| `tools/release/` | 外置私钥签名、网站渲染与本地发布材料生成工具 |
| `tools/`、`rules/`、`third_party/` | 规则转换、固定来源、许可证与测试 |

官网与反馈页面不依赖前端打包工具。反馈 API 使用 Python 3.12 标准库；部署参数和数据边界见 `services/feedback/README.md`。静态资源目录中不得放反馈附件、数据库、导出或签名文件。

## 分支与版本

日常开发以 `develop` 为集成分支。每项工作从 `develop` 建功能分支，经检查后合入；不直接在已发布版本上继续日常修改。

准备 `0.3.4` 时，从选定的 `develop` 提交建立 `release/0.3.4`。该分支只接收该版本的发布修复，修复也合回 `develop`。Android `versionName`、递增的 `versionCode`、官网模板和版本说明须一起核对。发布完成后，在实际构建的提交上创建不可移动的 `v0.3.4` 标签，并保留对应 APK、源码 ZIP、校验信息与发布记录。后续版本使用 `release/0.3.5`、`v0.3.5` 等新名称，不覆盖旧版材料或重写旧标签。

GitHub Release 与网站部署是独立动作。只有 APK 校验、网站下载、HTTPS 与实际用户流程核对完成后，才把发布状态记为完成；本地生成工具始终输出 `public_site_published: false`。

## 聚焦验证

修改前阅读根目录 `AGENTS.md`。按改动执行适当检查，不把模板预览、单元测试或模拟器结果当成真机效果。

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 -m unittest discover -s tools/release -p 'test_*.py'
python3 -m unittest discover -s services/feedback -p 'test_*.py'
node --test web/feedback/tests/test-client.mjs web/feedback/tests/test-wishes-client.mjs
node --test web/main/tests/download.test.mjs
```

Android 构建要求与固定工具版本见根 `README.md`。常规只构建 `gkd` 渠道；正式产物不能使用 debug 签名。无障碍开启、受限设置、后台运行和实际点击效果须在合适设备上单独验证。

## 应用签名与发布材料

正式签名私钥、口令和规则发布私钥必须位于仓库外；不能放进源码、GitHub Issue、Actions 日志或公开附件。仓库中的证书指纹、公钥和签名校验代码不是私钥。贡献者用自己的签名构建，无法覆盖官方签名版本。

设置 JDK 21 的 `JAVA_HOME`，将口令安全提供给当前进程的 `XADSKIP_STORE_PASSWORD`、`XADSKIP_KEY_PASSWORD` 环境变量。不要把口令直接写进命令、脚本或提交。以下路径为参数示例，需换成自己仓库外的路径：

```sh
python3 tools/release/sign_apk.py gkd-app/build/outputs/apk/gkd/release/your-unsigned.apk \
  --version 0.3.4 --version-code 7 \
  --build-tools /path/to/android-sdk/build-tools/37.0.0 \
  --keystore /private/path/release.jks --alias your-alias \
  --output-dir /private/path/signed/0.3.4
```

官方发布工具默认核对原有正式证书指纹。非官方构建须显式提供自己的 `--expected-cert`，并不得冒充官方包。签名工具输出 APK 与公开校验记录，不复制私钥或保存口令。

```sh
python3 tools/release/prepare_release.py 0.3.4 \
  --signed-dir /private/path/signed/0.3.4 \
  --notes /path/to/release-notes.txt \
  --rules /path/to/bundled-rules.json \
  --output-dir /path/outside/repository/prepared/0.3.4
```

准备工具核对 APK 校验记录、官方签名、包名、源码版本和文件哈希后，将 `web/main/` 预览模板渲染为 `site/` 正式材料，并生成 `update.json`、APK、对应源码 ZIP 和来源清单。输出目录必须是仓库外的新目录；它不会连接阿里云、推送 GitHub 或发布网页。先上传不可变下载文件与版本化资源，再更新版本清单和首页；部署前保留旧版页面、清单与回退记录。

## 规则候选与人工发布

用户反馈只进入私有反馈库，不能直接变成线上规则。维护者先检查并脱敏材料、确认应用与版本，编写候选规则；保留作者、来源与许可证。候选须经过结构、选择器与目标页面验证，并检查误触范围、已有开关和迁移兼容性。完成必要审查后，由维护者人工选择要发布的候选、递增规则版本，并使用仓库外的规则私钥签名。

正式规则发布到 `https://xadskip.com/rules/stable/manifest.json` 约定的稳定通道。客户端校验签名、内容哈希、结构和版本后才自动应用；失败继续使用旧规则。不能把用户上传截图、录屏、原始快照或私有反馈数据库放进规则仓库或发布包。代码仓库中的公钥不允许用于推断、存储或替代发布私钥。

## 反馈数据与公开协作

反馈文件与 SQLite 数据只放私有服务目录，例如 `/var/lib/xadskip-feedback`，不得处于任何网站根目录。服务没有公开附件查看或后台管理接口；仅维护者通过受限运维权限处理。反馈用于问题排查和规则维护；删除请求通过 `1345134186@qq.com` 和反馈编号处理，不承诺未实现的自动保留期限。

普通用户优先使用 `https://feedback.xadskip.com/`。GitHub Issue、提交历史和源码 ZIP 都是公开内容；上传日志或截图前必须确认不含个人信息。`.gitignore` 只能防止常见误加入，无法清除已经被跟踪的秘密；提交前还需检查实际文件清单和差异。
