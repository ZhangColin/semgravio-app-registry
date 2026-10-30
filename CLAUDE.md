# semgravio-app-registry

「语义引力」（semgravio）平台的 Service 服务，基于 cartisan-boot 框架。

## 核心文档

- [cartisan-boot 使用手册](docs/guide/cartisan-boot-使用手册.md) — 框架能力清单、API 文档和使用示例
- [限界上下文代码编写规范](docs/guide/限界上下文代码编写规范.md) — DDD 六边形架构落地指南

## 引用的 cartisan-boot 模块

- `cartisan-core` — DDD 基础类型、异常体系、架构注解、RequestContext
- `cartisan-web` — 统一响应体、全局异常处理、请求上下文、防重提交
- `cartisan-data-jpa` — BaseRepository、事件发布、审计、软删除、@Condition
- `cartisan-openapi` — 服务间签名验证、API Key 管理
- `cartisan-test` — ArchUnit 规则、测试基类

## 常用命令

- 编译：`mvn compile`
- 单元测试：`mvn test`
- 打包：`mvn package -DskipTests`
- 变异测试：`mvn org.pitest:pitest-maven:mutationCoverage`

## Agent skills

### Issue tracker

Issue 跟踪在本仓库的 GitHub Issues（ZhangColin/semgravio-app-registry），统一用 `gh` CLI 操作。See `docs/agents/issue-tracker.md`.

### Triage labels

使用默认五个 triage 标签：needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix。See `docs/agents/triage-labels.md`.

### Domain docs

Single-context 布局：根目录 `CONTEXT.md` + `docs/adr/`。See `docs/agents/domain.md`.

## 平台架构上下文
本服务（app-registry）是「语义引力」（semgravio）平台「应用/消费方」单一登记处（基础能力层）。原 semgravio-openapi，
因 identity SSO 需要登记「哪些应用能走 SSO」而升级。完整架构与决策在兄弟仓库
../semgravio-architecture/（起步包 docs/starters/app-registry.md）。

稳定不变式（务必遵守）：
- 一个应用一行，可持有 ApiKey（apiKey/apiSecret，供 cartisan-openapi 机机验签）和/或 SsoClient（client_id/client_secret/redirect_uris/scopes/grants，供 IdP OIDC）。两者独立、可空。
- cartisan-openapi 框架的 ApiKeyInfo/ApiKeyProvider 只服务 ApiKey——绝不把 OIDC 字段塞进去。SsoClient 走独立端点、identity 直接消费。
- 两类 secret 存储相反：apiSecret 必须可取回（加密存 + 传输加固，消费方要明文验 HMAC）；client_secret 必须 hash-only（像密码，永不返回）。
- GET /api/app-registry/api-keys/{apiKey} 只返 ApiKey；不返 SsoClient 字段。
- 签名 bootstrap 端点（`/api-keys/{apiKey}`）标 `@NoSignature` 跳过验签（死锁逃生舱），SSO bootstrap 端点（`/sso-clients/{clientId}`）标 `@RequireSignature` 强制验签（identity 预持凭证，无死锁）。所有 bootstrap 端点须 mTLS/内网/token/IP 加固。
- 本服务不做 OIDC token 签发/登录流（identity 做）；只持 SSO client 元数据。

深度（签名机制、callerAppId 来源、为什么 ApiKey/SsoClient 两存储、术语、决策）：
读架构仓库 app-registry.md、architecture.md §4/§6.1、CONTEXT.md、map.md。
本项目自己的设计演进 → 本项目的 CONTEXT.md + docs/adr/。
