# GKD 社区规则来源

2026-09-13 将以下两个公开项目的完整源码快照收录到 XAdSkip 私有仓库，供团队继续开发。快照文件保持原始字节，原作者及历史贡献者署名均保留。导入方式是固定 Git 提交的官方 codeload 源码包，不包含上游 Git 历史。

## 固定版本

| 项目 | 上游分支 | 精确提交 | 订阅版本 / ID | 许可证 |
|---|---|---|---|---|
| [甘霖](https://github.com/ganlinte/GKD-subscription) | `main` | `27c1178fdd9e3601b281680037a83c8167352808` | v71 / 233 | [MIT](../third_party/gkd-subscriptions/ganlinte/LICENSE) |
| [奥怪](https://github.com/aoguai/subscription) | `custom` | `4aa9643406e3fd58bd689fe9daab64fcbcf82bfe` | v88 / 86 | [GPL-3.0-only](../third_party/gkd-subscriptions/aoguai/LICENSE)，保留 [LEGAL.md](../third_party/gkd-subscriptions/aoguai/LEGAL.md) |

甘霖提交日期为 2026-06-24，奥怪为 2026-08-09。这里的 v71/v88 是订阅文件的 `version`，与各自 `package.json` 的 npm 包版本不同。许可标识与各仓库 `package.json` 一致，全文见原 LICENSE。奥怪的 LEGAL.md 包含维护者立场及使用说明，其“再分发说明”明确表示这些内容不构成 LICENSE 的补充许可条款或授权条件；该文件原样保留。

## 文件位置与实际统计

| 内容 | 甘霖 | 奥怪 |
|---|---|---|
| 完整源码快照 | [ganlinte/](../third_party/gkd-subscriptions/ganlinte/) | [aoguai/](../third_party/gkd-subscriptions/aoguai/) |
| 可编辑应用规则 | [src/apps/](../third_party/gkd-subscriptions/ganlinte/src/apps/) | [src/apps/](../third_party/gkd-subscriptions/aoguai/src/apps/) |
| 原生订阅文件 | [ganlin_gkd.json5](../third_party/gkd-subscriptions/ganlinte/dist/ganlin_gkd.json5) | [aoguai_gkd.json5](../third_party/gkd-subscriptions/aoguai/dist/aoguai_gkd.json5) |
| 上游适配列表 | [dist/README.md](../third_party/gkd-subscriptions/ganlinte/dist/README.md) | [dist/README.md](../third_party/gkd-subscriptions/aoguai/dist/README.md) |
| 应用条目 | 610 | 72 |
| 应用规则组 | 1389 | 285 |
| 全局规则组 | 3 | 9 |
| 快照文件数 | 643 | 105 |

数量直接统计自所收录的订阅 JSON5。奥怪的 285 指应用规则组数，不是应用数。规则包含开屏广告、其他广告和提示处理等多种用途；数量不代表实机有效性。两套规则之间可能覆盖同一应用，不能直接把应用数相加作为适配总数。

## 当前接入状态

自 **0.2.0-dev** 起，APK 随包附带李跳跳迁移草稿以及甘霖、奥怪的完整原生订阅。社区订阅文件、原 README、许可证和奥怪 LEGAL.md 位于 `gkd-app/src/main/assets/xadskip/community/`，通过 `python tools/bundle_community_rules.py` 从固定快照按字节复制；`--check` 可检查一致性。

使用入口：**订阅 → 右上角更多 → 导入甘霖内置规则 / 导入奥怪内置规则**。离线即可导入，随后手动打开所选订阅的开关并授权无障碍服务。两套订阅独立保存，重复导入保留已有规则和开关设置。

原始快照和 APK 内文件均保持原内容；导入到数据库时，甘霖改用 ID `2026091302`，奥怪改用 `2026091303`，订阅名称追加“（内置）”，移除 `updateUrl` 与 `checkUpdateUrl`，订阅和自动更新默认关闭。所有应用、规则组、规则、作者、版本和规则组默认开关均保留。这样内置副本与原上游订阅及李跳跳迁移草稿各自独立，不会自动联网覆盖团队修改。两套来源按包名有 68 个重叠应用，合计 614 个不同应用；建议选择一套启用。

快照包含上游的 `.github/`、构建脚本、锁文件等文件；嵌套目录中的工作流不会注册为 XAdSkip 根目录的 GitHub Actions 工作流。本次仅读取并核对文件，没有执行上游安装、构建或发布脚本。

## 更新与继续开发

1. 先确定上游目标提交，查看与当前固定版本之间的规则和许可证变化。
2. 从 [UPSTREAM.json](../UPSTREAM.json) 的来源取得新提交的源码包，在临时目录展开，与当前快照比较。保留团队改动，避免直接覆盖。
3. 更新对应快照、`UPSTREAM.json`、[community-snapshots.json](community-snapshots.json) 的归档和逐文件 SHA-256，以及本页统计。原始快照按字节保存，相关 Git 属性见根目录 `.gitattributes`。
4. 运行 `python tools/bundle_community_rules.py` 更新随包文件，再运行 `--check`。Android 构建任务会使用应用自带解析器检查两套规则，并在打包后逐字节确认三套订阅及社区许可文件确实存在于 APK 中；设备效果需另外验证。

## 本次核对范围

核对源码包 ZIP 完整性、全部 748 个文件与归档的逐字节一致性，以及 JSON5 订阅解析、应用 ID 唯一性和订阅版本文件的一致性。完整 SHA-256 清单位于 [community-snapshots.json](community-snapshots.json)，归档哈希及来源版本位于 [UPSTREAM.json](../UPSTREAM.json)。这些检查用于确认正确收录，不代表规则已通过 XAdSkip 引擎或真实设备验证。
