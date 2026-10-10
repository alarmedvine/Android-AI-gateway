v1.2.2 (versionCode 103) — 自定义供应商上游日志

## 为什么是它
Chat Completions 测试能通、Responses 模式报「超时」，且拉模型/请求头行为此前完全不可见（盲人摸象）。本版本只做一件事：把自定义供应商的上游请求/响应链路写进「记录」页日志。

## 日志能看到什么
- 每次上游调用一行简明日志：
  - 请求前：`自定义上游 → POST <完整URL>（协议=Chat|Responses，stream=…，model=…）`
  - 响应后：`自定义上游 ← HTTP <状态>（Content-Type …）`
  - 失败时：`HTTP <状态>：<上游错误消息摘要>`（如 403 缺头、404 无端点、429 限流）
- 拉模型同样入日志：`拉模型 → GET <URL>` / `拉模型 ← HTTP <状态>：<原因>`
- 打开「我的 → 详细日志」后追加：上游请求头（密钥脱敏为 sk-1****ab34 形式）、请求体原文、错误响应原文。

## 机制
- CustomProvider 新增 ProviderWireLog 钩子（brief/verbose），openChat 与 fetchModelsResult 在连接前/响应后打点；测试与 CLI 场景传 null 静默。
- GatewayEngine 注册自定义供应商时接入现有 RequestLog（「记录」页直接看，无需新页面）。
- 拉模型（未保存供应商时）日志直接写 requestLog。

## 其它
- 无协议/行为改动；旧配置无需迁移。
- versionCode 103，可直接覆盖安装。
