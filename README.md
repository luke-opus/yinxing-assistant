# 银杏助手

基于 GKD 源码和李跳跳社区规则迁移工具的 Android 协作开发项目。应用名称 **银杏助手**（原名 XAdSkip），副标题“帮您跳过部分广告，少点几下”。包名 `app.xadskip.android`，debug 包名追加 `.debug`。这是独立项目，不是李跳跳原源码或官方续作。

## 当前状态

- 导入 GKD 1.12.1 源码快照，保留无障碍服务、匹配引擎、规则管理和调试工具。
- 新增 JSON/JSON5 转换器、Java 包名哈希还原、规则报告和测试。
- 当前源码版本 **0.3.4（versionCode 7）**，保留 `app.xadskip.android` 包名与正式签名以支持覆盖升级；下载文件名继续使用 `XAdSkip-0.3.4.apk`。本段描述源码实现，是否构建、验证和发布以发布记录为准。
- 甘霖 v72、奥怪 v89 的固定源码、完整规则和原许可证仍随源码提供，见[社区规则来源](rules/COMMUNITY_SOURCES.md)。普通用户使用自动准备的统一内置规则库，无需选择来源或手动导入；首页可查看支持应用、规则状态和触发记录。原有迁移工具和李跳跳规则草稿作为开发资料保留。
- 326 个来源条目生成 **304 个应用、692 条规则草稿**，91 项需人工处理的条目见 `rules/conversion-report.json`。被跳过条目可能代表整套应用配置或单条规则，91 不是规则总数。
- 同意首次说明后，进入应用会独立查询官网规则和软件版本。规则更新校验签名、内容和版本后自动应用，失败保留旧规则；版本自动检查间隔至少 15 分钟，首页保留已发现的新版本提示，可在「关于」手动检查或关闭自动检查。软件下载校验大小和 SHA-256，下载与系统安装仍由用户确认。
- 首次使用提供三步无障碍图示；系统权限必须由使用者确认，不能用程序绕过。首页可暂停、查触发记录和打开反馈；反馈页面仅预填本应用版本，不自动上传日志、应用列表或设备信息。原发布工作流保存在 `docs/upstream/`，默认不注册上游资源上传任务。
- **历史验证：2026-09-13 的 Android 开发版已成功编译并生成 APK，尚未实机验证。** 当时由 GitHub Actions 构建，SDK 安装包名已修正为 `platforms;android-37.0`；成功记录见下文。0.3.0 的发布变化、源码构建与签名要求见 [RELEASE-0.3.0](docs/RELEASE-0.3.0.md)，0.3.1 的授权引导改进见 [RELEASE-0.3.1](docs/RELEASE-0.3.1.md)，本次构建结果以对应发布记录为准。
- 公开源码入口：[luke-opus/yinxing-assistant](https://github.com/luke-opus/yinxing-assistant)。对应 APK 也提供完整源码 ZIP；发布前须核对公开仓库和下载链接。

旧规则涵盖广告、升级、青少年模式和权限提示等用途，必须先筛选。来源库不包含李跳跳内部默认开屏识别逻辑，转换无法恢复该逻辑。应用仍有 GKD 的高级功能和部分上游说明入口，发布前需按 `docs/HANDOFF.md` 整理。

## 社区规则源码

2026-11-18 从官方源更新以下固定发布版本。应用数和规则组数直接统计自各自的 `dist/*.json5`，不同来源可能包含相同应用；规则组也包含广告以外的界面自动化用途。

| 来源 | 订阅版本 | 应用数 | 应用规则组 | 全局规则组 | 本地源码 |
|---|---:|---:|---:|---:|---|
| 甘霖 | v72 | 610 | 1389 | 3 | [发布快照](third_party/gkd-subscriptions/releases/) |
| 奥怪 | v89 | 72 | 280 | 9 | [发布快照](third_party/gkd-subscriptions/releases/) |

历史完整源码快照继续保留；本次固定发布快照与对应源码保存在 `third_party/gkd-subscriptions/releases/`，没有合并上游 Git 历史。固定提交、许可、更新步骤和完整性记录见[社区规则来源](rules/COMMUNITY_SOURCES.md)与 [`UPSTREAM.json`](UPSTREAM.json)。APK 保留来源文件和许可证，普通用户面对统一规则库，不需要区分作者来源。两套来源有 68 个重叠应用，原始数据按包名合计 614 个不同应用；统一规则使用合并策略处理重叠内容，并保留来源与作者归属。收录数量不等于实际可用数量，必须按设备效果验证。

统一规则库先从 APK 离线准备。同意首次说明后，前台进入事件会向 `https://xadskip.com/rules/stable/manifest.json` 查询规则；仅接受通过签名、文件哈希、结构和版本校验的更新，失败继续使用已有规则。自动应用规则与安装 APK 是不同流程；规则更新无需再次安装 APK，系统无障碍授权仍必须由用户亲自确认。升级与更新保留已有启用状态及规则开关；具体迁移行为以规则仓库实现和相关测试为准。手机后台限制、应用页面改版、规则关闭或未收录均可能影响效果。

首页与“关于”中的反馈入口打开 `https://feedback.xadskip.com/`，仅带 `xadskipVersion` 和 `xadskipVersionCode` 两个本应用版本参数。用户自行填写问题、选择并检查截图或录屏后主动发送；反馈用于问题排查与规则维护，可联系 `1345134186@qq.com` 并提供反馈编号申请删除。

网站新增[许愿池](https://feedback.xadskip.com/wishes.html)：用户可单独提交改进文字，也可附图片或短视频。内容先私有保存，开发者通过服务器 CLI 审核、整理后才公开愿望文字、处理状态和说明；公开状态包括“待处理”“计划中”“已完成 ✓”，附件与邮箱默认不公开。前端没有公开管理功能，只有真实服务器回执才显示提交成功。

“关于”中的使用说明、网页隐私和反馈入口由银杏助手提供；应用数据处理另有随包的本地隐私说明，可在首次使用说明和“关于”中离线查看。保留 GKD 上游来源、社区规则与许可证致谢；移除上游捐赠、Google Play 跳转以及使用上游固定仓库生成日志/快照链接的 GitHub 上传功能。本地导出与系统分享保留，导出前请检查日志、应用列表、设置和快照中的个人信息。高级上游技术文档继续作为明确标注的参考。


## 验证

```powershell
python -m pip install -r tools/requirements.txt
python tools/bundle_community_rules.py --check
python tools/convert_ltt.py
python -m unittest discover -s tools -p "test_*.py" -v
npm ci --prefix tools/selector-check --ignore-scripts
npm test --prefix tools/selector-check
```

13 项转换器测试通过。1384 个选择器通过已发布的 `@gkd-kit/selector@0.5.23` 解析，模拟节点树上的“条件存在才点击目标”检查通过。上游源码自带选择器为 0.6.0，当时 npm 未发布该版本；以上检查不能替代本工程的 Android 编译和设备测试。

2026-09-13，提交 `d632d37` 的 [GitHub 规则校验通过](https://github.com/luke-opus/xadskip/actions/runs/34738777976)，包括重新生成规则后与已提交文件一致的检查。[Android 首次构建记录](https://github.com/luke-opus/xadskip/actions/runs/34738779366)显示 `Failed to find package 'platforms;android-37'`。经核对 Google 官方 SDK 仓库，正确安装包名为 `platforms;android-37.0`；这是安装标识错误，不能据此判断整个 SDK 37 不可用。

修复后的提交 `1b71aec` 已通过 [Android development build #2](https://github.com/luke-opus/xadskip/actions/runs/34739138107)，任务 `:gkd-app:assembleGkdDebug` 输出 `BUILD SUCCESSFUL`，APK 已上传到该次运行的 Artifacts。此结果验证了依赖解析、Android 编译和 debug 打包；不等于实机运行、广告识别效果或正式发布验证完成。

## 构建

构建是把源码编译并打包成 APK；SDK 是构建所需工具和平台库。团队使用以下固定版本，避免各自随意升级造成构建差异：

| 组件 | 工程版本 | 用途与配置位置 |
|---|---|---|
| JDK | Eclipse Temurin 21；成功构建实际为 21.0.12.1+1-LTS | 运行 Gradle；`gradle/gradle-daemon-jvm.properties` 和 Android 工作流固定主版本 21 |
| Gradle | 9.7.1 | 使用仓库自带 Wrapper，`gradle/wrapper/gradle-wrapper.properties`；已固定官方 SHA-256 |
| Android Gradle Plugin（AGP） | 9.3.2 | Android 构建插件，`gradle/libs.versions.toml` |
| Kotlin / Compose 编译插件 | 2.4.10 | Kotlin 编译与插件，`gradle/libs.versions.toml` |
| KSP | 2.3.11 | 代码生成，`gradle/libs.versions.toml` |
| Android SDK Platform | `platforms;android-37.0` | SDK Manager 安装包标识，末尾 `.0` 不能省略 |
| compileSdk / targetSdk | 37 / 37 | 编译与目标 API 级别，根目录 `build.gradle.kts` |
| Android SDK Build Tools | 37.0.0 | APK 打包工具，根目录 `build.gradle.kts` |
| minSdk | 26（Android 8.0） | 最低支持系统；不代表已完成所有版本的实机验证 |
| Java / Kotlin JVM 字节码目标 | 11 | 与运行 Gradle 的 JDK 21 是不同概念 |
| Compose UI / Material3 | 1.12.0 / 1.4.0 | 界面库，与 Compose 编译插件版本不同 |
| Room / SQLite | 3.0.1 / 2.7.0 | 数据库与驱动 |
| Python / json5 | 3.12 / 0.12.1 | 规则转换和测试 |
| Node.js / 选择器检查包 | 24 / `@gkd-kit/selector@0.5.23` | 规则校验；Android 工程内选择器源码仍为 0.6.0 |

完整依赖（包括 Ktor 3.5.2、Lifecycle 2.11.0、Coil 3.5.0 等）以 [`gradle/libs.versions.toml`](gradle/libs.versions.toml) 为准；不要只改 README 中的数字。AGP 9.3 官方支持最高 API 37，要求 Gradle 至少 9.5.0、JDK 至少 17；本项目选用上表组合。来源：[AGP 兼容说明](https://developer.android.com/build/releases/agp-9-3-0-release-notes)、[Android 17 配置说明](https://developer.android.com/about/versions/17/setup-sdk)、[Google SDK 仓库包列表](https://dl.google.com/android/repository/repository2-3.xml)。

上表 Android 组合已在构建 #2 中成功验证。`gradlew --version` 显示的 Kotlin 2.4.0 是 Gradle 自带的脚本运行组件；应用的 Kotlin 编译插件版本为 2.4.10。两者用途不同，不需要把数字改成一致。JDK 主版本固定为 21，后续 CI 可能使用该系列的新补丁版本；SDK Platform 包的当前 revision 也由 SDK 仓库维护，具体安装版本保留在工作流的 `Show build tool versions` 日志中。

0.3.4 发布时提供的 `XAdSkip-0.3.4-source.zip` 是完整源码快照，不包含 `.git` 目录或 Git bundle；构建不需要访问私有开发仓库。解压后进入 `XAdSkip-0.3.4` 目录，先创建本地 Git 仓库并提交源码，供构建脚本读取提交元数据。下面的提交身份仅用于这次本地源码快照，不修改系统 Git 配置，也不会推送到任何服务器：

```powershell
cd XAdSkip-0.3.4
git init
git add .
git -c user.name="Source build" -c user.email="source-build@example.invalid" commit -m "Import XAdSkip 0.3.4 source snapshot"
```

如果通过 Git 克隆取得源码且已有提交，可跳过以上初始化。源码 ZIP 新建的本地提交 ID 会与发布者原提交不同，不代表源码内容发生变化。

然后安装 JDK 21，设置 `JAVA_HOME`；通过 Android Studio 的 SDK Manager 或以下命令安装 SDK，并设置 `ANDROID_HOME` 指向 SDK 根目录（也可使用不提交到 Git 的 `local.properties`）。无需另外安装系统 Gradle。

```powershell
sdkmanager --channel=0 'platforms;android-37.0' 'build-tools;37.0.0'
.\gradlew.bat :gkd-app:assembleGkdRelease :gkd-app:testGkdDebugUnitTest --no-daemon
```

macOS / Linux 使用 `sh ./gradlew` 替换 `.\gradlew.bat`，可直接运行 ZIP 中未带可执行权限的 Wrapper。仅构建 `gkd` 渠道；正式产物位于 `gkd-app/build/outputs/apk/gkd/release/`。单元测试使用工程实际提供的 `testGkdDebugUnitTest` 任务，公开安装包仍只使用 release 产物。未提供签名参数时会生成未签名 APK，不会使用 debug 密钥代签。安装前须使用自己的签名，配置方式见 [RELEASE-0.3.0](docs/RELEASE-0.3.0.md)；私钥与口令应保存在源码目录之外，不进入 Git 或公开源码包。

原有 GitHub 工作流 **Actions → Android development build → Run workflow → develop** 仍用于团队开发验证，仅手动触发，不自动发布正式版。该工作流的 `XAdSkip-development-apk` 是 debug 产物，保留 7 天，本地对应目录为 `gkd-app/build/outputs/apk/gkd/debug/`。需要本地开发包时可单独运行 `:gkd-app:assembleGkdDebug`。生成任何 APK 后仍需进行设备测试。

## 公开源码与协作

Android 应用、官网模板、反馈表单、私有反馈服务程序和通用发布工具均在本仓库中。目录说明、`develop` / `release/版本` / `v版本` 工作流、外置签名与规则人工发布要求见 [开发与发布](docs/DEVELOPMENT.md)。反馈数据库、用户附件及签名私钥不属于公开源码。

公开项目使用 [luke-opus/yinxing-assistant](https://github.com/luke-opus/yinxing-assistant)。开发和发布保留完整上游归属、许可证及固定规则源码快照。普通贡献使用分支与 Pull Request，修改前先阅读 `AGENTS.md`；公开源码不包含正式签名私钥、生产凭据和私有运行数据。

```powershell
git clone https://github.com/luke-opus/yinxing-assistant.git
cd yinxing-assistant
git switch -c feature/your-change
```

从源码自行构建需使用自己的签名，不能覆盖官方签名安装的应用。正式发布包由维护者使用原签名构建，以保留现有用户的覆盖升级能力。旧的私有发布脚本作为历史工具保留，不用于公开正式发布。

## 来源与许可证

精确提交、输入文件哈希见 `UPSTREAM.json`。原 README 和许可证均保留。

- GKD 和银杏助手（XAdSkip）新增程序：GPL-3.0-only，见根目录 `LICENSE`。
- 李跳跳来源及转换后的规则数据：按 `third_party/ltt/LICENSE` 的 AGPL-3.0 许可保留，见 `rules/NOTICE.md`。
- 甘霖规则快照：MIT，见 `third_party/gkd-subscriptions/ganlinte/LICENSE`；奥怪规则快照：GPL-3.0-only，见 `third_party/gkd-subscriptions/aoguai/LICENSE`，同时保留其 `LEGAL.md`。两者及历史贡献者的原有署名均保留。
- 私有开发与对外分发是两个阶段。对外发 APK 时须履行许可证的对应源码、声明及接收者权利要求；团队仓库的管理权限不必公开。
- GKD 原 README 另有用途限制文字，已保存在 `docs/upstream/GKD-README.md`，商用前需澄清与 GPL 声明的关系。

来源：[GKD](https://github.com/gkd-kit/gkd)、[规则备份](https://github.com/xue-mark/BACKUP-LiTiaotiao-Custom-Rules)、[选择器文档](https://gkd.li/guide/selector)。
