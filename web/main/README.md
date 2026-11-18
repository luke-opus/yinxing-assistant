# 官网源码模板

这是银杏助手 0.3.4 的静态源码预览，不包含 APK 或源码 ZIP 下载产物。直接打开时显示“发布准备中”，`data-release-ready="false"` 会阻止下载入口启用；大小和 SHA-256 留给发布工具填充。

```sh
python3 -m http.server 8080 --directory web/main
```

维护页面直接修改这里的 HTML、CSS、JS、SVG。正式材料由 `tools/release/prepare_release.py` 在签名校验后生成到仓库外，不把生成结果覆盖回模板。发布流程见 `docs/DEVELOPMENT.md`。
