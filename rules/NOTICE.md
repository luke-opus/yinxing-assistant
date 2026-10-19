# 规则来源与迁移说明

## GKD 社区规则快照

`third_party/gkd-subscriptions/ganlinte/` 保存甘霖 v71 的完整源码快照（MIT）；`third_party/gkd-subscriptions/aoguai/` 保存奥怪 v88 的完整源码快照（GPL-3.0-only）。原许可证、贡献者署名、README、奥怪的 LEGAL.md 和全部原始文件均保留。确切提交和逐文件 SHA-256 见 `UPSTREAM.json` 与 `rules/community-snapshots.json`。

自 0.2.0-dev 起，两套原始订阅文件、原 README、许可证和奥怪 LEGAL.md 随 APK 保存于 `assets/xadskip/community/`，由 `tools/bundle_community_rules.py` 按固定快照复制。应用内导入时使用独立订阅 ID、清除更新地址并关闭订阅及自动更新；原规则、作者、版本和规则组开关均保留。来源、统计及后续更新步骤见 [COMMUNITY_SOURCES.md](COMMUNITY_SOURCES.md)。

## 李跳跳社区规则迁移

`gkd-app/src/main/assets/xadskip/ltt-drafts.json` 由 `third_party/ltt/BasicRules.json` 转换，保留李跳跳社区贡献者署名和来源 AGPL-3.0 许可。原始输入、许可证、应用列表和提交哈希均在仓库内。

所有草稿默认关闭。属性映射是迁移假设：完整资源 ID 对应 `id`，带下划线的短标识对应 `vid`，其余对应 `text` 或 `desc`；`=`、`+`、`-` 对应精确、前缀、后缀匹配，无前缀按包含匹配。

以下情况不猜测：包名哈希冲突/未知、坐标、返回键、`&` 联合条件、重复点击、等待、强制点击和未知字段。报告逐项记录原因。GKD 搜索和次数重置与李跳跳不同；草稿额外限制 10 秒窗口、单次动作和 1500 毫秒冷却，这会改变行为，须实机审查。

这不是李跳跳内置开屏规则，也不是当前 App 已适配的证明。维护者应记录包名、App 版本、设备/系统、脱敏快照、预期目标和误触场景。只有验证过的广告规则才进入后续发布包。
