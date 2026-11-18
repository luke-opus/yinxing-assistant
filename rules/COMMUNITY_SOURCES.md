# GKD 社区规则来源

2026-11-18 从原作者 GitHub 仓库核对最新正式发布，固定下载以下发布提交的完整源码快照，包含 src、构建脚本、订阅发布文件、许可证、README 和相关声明。原始文件按字节保存，不执行上游安装或构建脚本，也不导入 Git 历史。

## 当前随包版本

| 项目 | 正式发布 | 精确提交 | 订阅版本 / 原始 ID | 许可证 |
|---|---|---|---|---|
| [甘霖](https://github.com/ganlinte/GKD-subscription) | [v72](https://github.com/ganlinte/GKD-subscription/releases/tag/v72) | `4746d192b4c57a4b62ed92a7f5deaf79e64fa811` | v72 / 233 | [MIT](../third_party/gkd-subscriptions/releases/ganlinte-v72/LICENSE) |
| [奥怪](https://github.com/aoguai/subscription) | [v89](https://github.com/aoguai/subscription/releases/tag/v89) | `e0ad57290fed632e7305f67f92fcca8e775fb825` | v89 / 86 | [GPL-3.0-only](../third_party/gkd-subscriptions/releases/aoguai-v89/LICENSE)，保留 [LEGAL.md](../third_party/gkd-subscriptions/releases/aoguai-v89/LEGAL.md) |

上表版本来自订阅文件的 `version`，与 npm 包版本不同。奥怪发布标签与当前分支中的规则文件已逐字节比较一致，发布记录仍固定到标签提交。

| 内容 | 甘霖 | 奥怪 |
|---|---|---|
| 完整源码快照 | [ganlinte-v72/](../third_party/gkd-subscriptions/releases/ganlinte-v72/) | [aoguai-v89/](../third_party/gkd-subscriptions/releases/aoguai-v89/) |
| 原生订阅文件 | [ganlin_gkd.json5](../third_party/gkd-subscriptions/releases/ganlinte-v72/dist/ganlin_gkd.json5) | [aoguai_gkd.json5](../third_party/gkd-subscriptions/releases/aoguai-v89/dist/aoguai_gkd.json5) |
| 应用条目 | 610 | 72 |
| 应用规则组 | 1389 | 280 |
| 全局规则组 | 3 | 9 |

数量直接统计自订阅 JSON5，规则组数不代表应用数或实机有效性。两套来源按包名有 68 个重叠应用，共 614 个不同应用。

## 应用接入与升级

社区文件和原始声明随 APK 保存于 `gkd-app/src/main/assets/xadskip/community/`，通过 `python tools/bundle_community_rules.py` 按字节复制，`--check` 检查一致性。

全新安装自动离线导入并启用随包规则组最多的一套，目前为甘霖。规则作者设置的类别和规则组默认开关保持原样，不自动开启全部规则。手动入口仍为 **订阅 → 右上角更多 → 导入甘霖内置规则 / 导入奥怪内置规则**，手动新增的另一套默认关闭。

内置副本使用固定独立 ID：甘霖 `2026091302`，奥怪 `2026091303`；名称追加“（内置）”，清除 `updateUrl` 与 `checkUpdateUrl`，关闭规则网络更新。自 0.3.3 起，APK 更新后会检查已有内置副本的版本，只用更新的随包版本替换其规则内容，保留订阅启用状态、排序、规则组开关及排除设置。已删除的订阅不会自动恢复。本地自定义订阅、用户另行添加的订阅和李跳跳迁移草稿不参与这次刷新。单个内置文件缺失或损坏沿用逐订阅错误提示，不阻断其他订阅加载。

## 历史源码与维护

`third_party/gkd-subscriptions/ganlinte/` 和 `aoguai/` 仍分别保留 2026-09-13 收录的 v71、v88 完整源码快照。本次对应 v72/v89 的完整源码已另存于 `releases/`，与随包 dist 文件逐字节核对一致；旧快照作为历史留底保留。旧快照的来源和逐文件校验值保存在 `UPSTREAM.json` 的 `historicalSourceSnapshot` 与 `rules/community-snapshots.json` 的 `historicalSources` 中。

后续更新时先核对原作者正式发布及其固定提交，下载完整源码归档并安全展开，核对订阅、版本文件和许可证声明，更新 `UPSTREAM.json` 与 `rules/community-snapshots.json` 的版本、来源和 SHA-256，再执行 `python tools/bundle_community_rules.py`。当前引擎的规则解析兼容性由 `XAdSkipRulePackTest.bundledRulesAreAcceptedByTheCurrentEngine` 验证；真实手机上的匹配效果需要单独验证。
