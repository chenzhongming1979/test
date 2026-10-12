# 贡献指南（CONTRIBUTING）

感谢关注 JY-Engine！提交贡献前请完成以下流程。

## 首次贡献（一次性）

> 完整操作指引（含 Issue 模板、FAQ）见 [CLA-GUIDE.md](CLA-GUIDE.md)。

1. **签署 CLA**：开一个 Issue，标题格式 `签署 CLA：@你的Gitee用户名`，正文包含以下声明，并一并提供**真实姓名与可用联系方式**（手机号或邮箱，仅用于签署登记与身份确认，详见 [CLA-SIGNED.md](CLA-SIGNED.md)）：
   > 我已阅读并完全同意 [CLA.md](CLA.md) 全部条款，特此签署。
   维护者核实后将把您登记到 [CLA-SIGNED.md](CLA-SIGNED.md)。
2. 已在名单中的贡献者跳过此步，后续 PR 直接勾选声明即可。

## 贡献流程

```
fork 仓库 → 创建分支 → 开发 → 提交 PR（勾选 CLA 声明）→ 评审 → 合并
```

1. **Fork 并建分支**：分支名建议 `feat/xxx`、`fix/xxx`、`docs/xxx`。
2. **开发前先读文档**（文档是设计真相源，代码是事实真相源）：
   - 改引擎（autolua2/）：`docs/01-架构总览.md` → `docs/06-开发铁律与踩坑手册.md`
   - 写 Lua 脚本：`docs/02-Lua API规范.md`（权威）
   - 云控/端到端：`engine/docs/AGENT_GUIDE.md` + `docs/features/cloud/`
   - **先改文档、后改代码**：契约变更必须同步 `docs/` 对应域文档
3. **提交规范**：commit message 用中文，格式 `类型(范围): 描述`，如 `fix(engine): xxx`、`docs(api): xxx`、`chore(repo): xxx`。
4. **自测**：引擎改动须通过 `tools/selftest`（基线 PASS=442 不得回退）；文档一致性核查 `python docs\check_docs_consistency.py` 全 PASS。
5. **提交 PR**：使用 PR 模板，如实填写变更说明并勾选 CLA 声明。

## 禁止提交的内容

- 逆向产物、第三方 APK/IPK 等外部知识产权文件（`app/` 目录不入库）；
- 构建产物、日志、本地配置（`.gitignore` 已覆盖，请勿绕过）；
- 硬编码的密钥、口令、云控 secret 等敏感信息。
