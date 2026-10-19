# XAdSkip

基于 GKD 源码和李跳跳社区规则迁移工具的 Android 协作开发项目。应用名称 **XAdSkip**，包名 `app.xadskip.android`，debug 包名追加 `.debug`。这是独立开发版，不是李跳跳原源码或官方续作。

## 当前状态

- 导入 GKD 1.12.1 源码快照，保留无障碍服务、匹配引擎、规则管理和调试工具。
- 新增 JSON/JSON5 转换器、Java 包名哈希还原、规则报告和测试。
- 当前源码版本 **0.2.0-dev（versionCode 2）**，随包加入甘霖 v71、奥怪 v88 的完整订阅文件及原许可证，见[社区规则来源](rules/COMMUNITY_SOURCES.md)。全新安装首次启动会自动离线导入并启用覆盖最多的甘霖订阅，保留规则作者设置的规则组和类别默认开关。手动入口为 **订阅 → 右上角更多 → 导入甘霖/奥怪内置规则**，手动新增的订阅仍默认关闭；原有李跳跳迁移草稿仍随包保留。
- 326 个来源条目生成 **304 个应用、692 条规则草稿**，91 项需人工处理的条目见 `rules/conversion-report.json`。被跳过条目可能代表整套应用配置或单条规则，91 不是规则总数。
- 应用入口：**订阅 → 右上角更多 → 导入 XAdSkip 迁移草稿**。订阅和各规则组默认关闭；重复导入保留已有编辑。
- 使用独立包名、名称、版本和启动图标。关闭上游 APK 更新入口；原发布工作流保存在 `docs/upstream/`，默认不注册上游资源上传任务。
- **Android 开发版已成功编译并生成 APK，尚未实机验证。** 首次云端构建的 SDK 安装包名已修正为 `platforms;android-37.0`；成功记录和版本说明见下文。本机没有配置 JDK/Android SDK，APK 由 GitHub Actions 构建。
- 开发仓库：[luke-opus/xadskip](https://github.com/luke-opus/xadskip)，已创建为 **Private**。协作者 `wangsen19900105` 的邀请已发送，等待对方接受。

旧规则涵盖广告、升级、青少年模式和权限提示等用途，必须先筛选。来源库不包含李跳跳内部默认开屏识别逻辑，转换无法恢复该逻辑。应用仍有 GKD 的高级功能和部分上游说明入口，发布前需按 `docs/HANDOFF.md` 整理。

## 社区规则源码

2026-09-13 收录以下固定版本，供团队继续开发。应用数和规则组数直接统计自各自的 `dist/*.json5`，不同来源可能包含相同应用；规则组也包含广告以外的界面自动化用途。

| 来源 | 订阅版本 | 应用数 | 应用规则组 | 全局规则组 | 本地源码 |
|---|---:|---:|---:|---:|---|
| 甘霖 | v71 | 610 | 1389 | 3 | [ganlinte](third_party/gkd-subscriptions/ganlinte/) |
| 奥怪 | v88 | 72 | 285 | 9 | [aoguai](third_party/gkd-subscriptions/aoguai/) |

保留各仓库全部文件的原始内容，包括 `src/`、`dist/`、构建脚本、锁文件和贡献说明；没有合并上游 Git 历史。固定提交、许可、更新步骤和完整性记录见[社区规则来源](rules/COMMUNITY_SOURCES.md)与 [`UPSTREAM.json`](UPSTREAM.json)。APK 内原始订阅文件按字节复制；导入时使用独立标识并清除网络更新地址，保留原规则、作者和规则组开关。重复导入保留用户修改。两套来源有 68 个重叠应用，按包名合计 614 个不同应用；建议选择一套启用，并按设备实际效果验证。

默认规则只初始化一次，且只在尚无订阅的全新安装上执行；已有安装（包括只有本地订阅的旧版本）不会自动新增或开启规则。用户之后关闭、编辑或删除订阅，重启应用不会恢复默认配置。内置订阅不启用网络更新；默认导入不授予或开启系统无障碍权限，仍需用户在系统中授权。覆盖数量不代表每条规则默认启用，也不代表已完成对应应用的实机验证。

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

本地构建：先安装 JDK 21，设置 `JAVA_HOME`；通过 Android Studio 的 SDK Manager 或以下命令安装 SDK，并设置 `ANDROID_HOME` 指向 SDK 根目录（也可使用不提交到 Git 的 `local.properties`）。无需另外安装系统 Gradle。

```powershell
# 构建脚本需要工程具有 Git 提交
sdkmanager --channel=0 'platforms;android-37.0' 'build-tools;37.0.0'
.\gradlew.bat :gkd-app:assembleGkdDebug --no-daemon
```

仅构建 `gkd` 渠道。GitHub 的 Android 工作流仅手动触发，不自动发布 release。debug APK 只供团队验证，签名密钥不入库。

云端构建入口：仓库 **Actions → Android development build → Run workflow → main**。构建成功后，在该次运行的 Artifacts 下载 `XAdSkip-development-apk`（保留 7 天）；解压后得到 APK。本地产物位于 `gkd-app/build/outputs/apk/gkd/debug/`。成功生成 APK 后仍需进行设备测试。

从随附 Git bundle 恢复本地仓库（包含初始提交）：

```powershell
git clone XAdSkip.git.bundle xadskip
cd xadskip
git remote remove origin
```

## 私有协作

在个人账号下新建 **Private 独立仓库**，各自使用自己的 GitHub 账号，按用户名邀请。公共项目的 GitHub Fork 不能直接实现这一私有设置。

当前仓库由 `luke-opus` 所有，仅邀请 `wangsen19900105`。协作者使用自己的账号登录 GitHub，接受仓库邀请后即可克隆和提交分支：

```powershell
git clone https://github.com/luke-opus/xadskip.git
cd xadskip
git switch -c feature/your-change
```

提交后创建 Pull Request，由另一位开发者检查再合并。分工和首轮验收见 `docs/HANDOFF.md`。

`scripts/publish-private.ps1` 只推送到当前登录用户拥有的私有仓库，推送前检查已有成员和待接受邀请，不会强推或改为公开：

```powershell
gh auth login --hostname github.com
.\scripts\publish-private.ps1 -RepositoryName xadskip -Collaborators '另一位GitHub用户名'
```

如果实际是你加另外两人，传入两个用户名。邀请必须由对方接受。组织所有者也能访问组织私有库，因此“仅两人访问”优先采用个人私有库；账号已有的 GitHub App 等授权也需单独核对。

## 来源与许可证

精确提交、输入文件哈希见 `UPSTREAM.json`。原 README 和许可证均保留。

- GKD 和 XAdSkip 新增程序：GPL-3.0-only，见根目录 `LICENSE`。
- 李跳跳来源及转换后的规则数据：按 `third_party/ltt/LICENSE` 的 AGPL-3.0 许可保留，见 `rules/NOTICE.md`。
- 甘霖规则快照：MIT，见 `third_party/gkd-subscriptions/ganlinte/LICENSE`；奥怪规则快照：GPL-3.0-only，见 `third_party/gkd-subscriptions/aoguai/LICENSE`，同时保留其 `LEGAL.md`。两者及历史贡献者的原有署名均保留。
- 私有开发与对外分发是两个阶段。对外发 APK 时须履行许可证的对应源码、声明及接收者权利要求；团队仓库的管理权限不必公开。
- GKD 原 README 另有用途限制文字，已保存在 `docs/upstream/GKD-README.md`，商用前需澄清与 GPL 声明的关系。

来源：[GKD](https://github.com/gkd-kit/gkd)、[规则备份](https://github.com/xue-mark/BACKUP-LiTiaotiao-Custom-Rules)、[选择器文档](https://gkd.li/guide/selector)。
