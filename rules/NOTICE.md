# 规则来源与迁移说明

## GKD 社区规则快照

当前随包为甘霖 v72（MIT）和奥怪 v89（GPL-3.0-only），对应固定提交的完整源码及发布文件保存于 `third_party/gkd-subscriptions/releases/`。原订阅、许可证、作者署名、README 和奥怪 LEGAL.md 均按原始字节保留。历史 v71/v88 完整源码快照保留在原目录；v72/v89 的 src、构建脚本和声明已一并保存，未运行上游代码。确切提交与逐文件 SHA-256 见 `UPSTREAM.json` 和 `rules/community-snapshots.json`。

两套订阅及声明通过 `tools/bundle_community_rules.py` 保存于 APK 的 `assets/xadskip/community/`。新安装默认启用规则组最多的一套；原规则组默认开关不变。内置副本使用独立订阅 ID，关闭网络更新；应用升级时刷新已有内置副本，保留用户启用状态和规则组配置。其他订阅及自定义规则不参与刷新。来源和数量见 [COMMUNITY_SOURCES.md](COMMUNITY_SOURCES.md)。

## 李跳跳社区规则迁移

`gkd-app/src/main/assets/xadskip/ltt-drafts.json` 由 `third_party/ltt/BasicRules.json` 转换，保留李跳跳社区贡献者署名和来源 AGPL-3.0 许可。原始输入、许可证、应用列表和提交哈希均在仓库内。

所有草稿默认关闭。属性映射是迁移假设：完整资源 ID 对应 `id`，带下划线的短标识对应 `vid`，其余对应 `text` 或 `desc`；`=`、`+`、`-` 对应精确、前缀、后缀匹配，无前缀按包含匹配。

以下情况不猜测：包名哈希冲突/未知、坐标、返回键、`&` 联合条件、重复点击、等待、强制点击和未知字段。报告逐项记录原因。GKD 搜索和次数重置与李跳跳不同；草稿额外限制 10 秒窗口、单次动作和 1500 毫秒冷却，这会改变行为，须实机审查。

这不是李跳跳内置开屏规则，也不是当前 App 已适配的证明。维护者应记录包名、App 版本、设备/系统、脱敏快照、预期目标和误触场景。只有验证过的广告规则才进入后续发布包。
