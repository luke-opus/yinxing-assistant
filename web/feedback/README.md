# 反馈页面源码

问题反馈静态文件为 `feedback.html`、`feedback.css`、`feedback.js`、`icon.svg`；许愿池使用 `wishes.html`、`wishes.css`、`wishes.js`，与反馈页共用样式和同源 API。生产服务器可把 `/` 映射到 `feedback.html`，同源 `/api/feedback` 转发给 `services/feedback/` 的 API。不要把 `tests/` 或私有存储作为上传目录。

来自应用的链接只预填 `xadskipVersion`。页面不读取设备标识、应用列表或本机日志，选文件只在当前页面预览；点击发送并同意后才上传。后端校验和存储说明见 `services/feedback/README.md`。

```sh
node --test web/feedback/tests/*.mjs
```

测试使用模拟页面和合成文件，不联网、不读取真实用户附件。

许愿池 `POST /api/wishes` 接受必填 `description`、可选 `email`、必选 `consent=true`、幂等 `requestId` 与可选 `attachments`；真实回执包含 `ok=true`、`wishId`、`status=pending_review`。`GET /api/wishes?limit=20&cursor=…` 仅返回审核公开的文字、状态、开发者说明及不透明 `nextCursor`。公开列表不展示附件、邮箱或未审核内容；管理员审核/完成通过服务器 CLI，前端不提供写入公开状态的接口。
