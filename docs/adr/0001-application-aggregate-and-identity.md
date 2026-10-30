# ADR-0001：应用聚合边界与标识模型

- **状态**：Accepted
- **日期**：2026-07-28
- **关联**：架构仓库 `docs/starters/app-registry.md`、`CONTEXT.md`「应用注册」条目；本项目 `CONTEXT.md`

## 背景（Context）

服务从 `semgravio-openapi` 升级为 `app-registry`，要持 **ApiKey**（apiKey/apiSecret，供 `cartisan-openapi` 机机验签）+ **SsoClient**（client_id/client_secret/redirect_uris/scopes/grants，供 IdP OIDC）两类身份。

现状是单一 `ApiKey` 聚合 + 单表 `oas_api_keys`，`apiKey` 既是应用标识又是签名 key，无法表达"一个应用持 ApiKey 和 SsoClient"。需要确定应用与这两类凭证的聚合边界、基数、以及标识体系。

约束：
- 架构稳定不变式：一个应用一行持 ApiKey 和/或 SsoClient、两者独立可空、两类 secret 存储相反、`ApiKeyInfo` 只服务 ApiKey、`GET /api/app-registry/api-keys/{apiKey}` 只返 ApiKey。
- `cartisan-openapi` 契约：`ApiKeyProvider.getByAppId(apiKey) → ApiKeyInfo(appId/appName/apiSecret/permissions/status)`；`callerAppId` 锁死 = `X-App-Id` = `api_key`；框架禁改。

## 决策（Decision）

1. **三聚合根 / 三表**：`RegisteredApp`（`ar_registered_apps`）/ ApiKey / SsoClient（表名下轮定，同 `ar_` 前缀），以 app 内部 `id` 关联。**非**单表值对象。
   - 判据：ApiKey 与 SsoClient 之间无跨边界不变式（独立轮换/启停/唯一性约束/被寻址）；塞同聚合是"大聚合"反模式。

2. **基数 1:1**：ApiKey 与 SsoClient 对 app 都 1:1。
   - 多 apiKey / 多 client 的动机（多环境、多子系统、多权限范围）通常更适合建模为**多个独立 app**。
   - 零停机轮换用「当前 secret + 上一份 secret」影子列解决，不需要 1:N。
   - 多 key 会引入 per-key 审计/限流/计量/吊销层，复杂度跳一档，当前无此需求。

3. **标识谱系**（四者职责不重叠）：

   | 标识 | 归属 | 性质 |
   |---|---|---|
   | `id`（Tsid） | app | 内部主键，不外暴露 |
   | `app_code` | app | 稳定公开 slug，**创建时填写、不可修改**；与凭证解耦的应用级稳定身份 |
   | `api_key` | ApiKey | 凭证标识，**可完全轮换**；= 框架 `X-App-Id` / `callerAppId` |
   | `client_id` | SsoClient | OIDC 标识，**可完全轮换** |

4. **`app_code` 的角色**：与凭证解耦的应用级稳定身份，使 `api_key`/`client_id` 可完全轮换而不丢应用身份。业界三件套分离（GCP `project_id`、Auth0 `client_id`、Stripe `acct_xxx` 均把稳定标识 / 显示名 / 凭证分开）。不可进 `callerAppId` 热路径（框架锁死），仅作跨域引用/计量/归属键。把凭证 id 当业务引用键是反模式（GCP「links not keys」），故 `app_code` 与凭证解耦。

5. **`RegisteredApp` 是治理锚点**：持 name/description/应用级状态，不退化成空壳。app 禁用是否级联到 ApiKey/SsoClient——用"查询时 join app 校验应用级状态"或领域事件最终一致，**不**靠同事务强一致。（**已定**：选 join app 校验 + `ApiKeyInfo.status` 联合 `app.status`/`api_key.status`，见 CONTEXT.md「聚合边界」。）

6. **命名**：上下文码 `ar`（AppRegistry）；实体 `RegisteredApp` → 表 `ar_registered_apps`（表名对应实体、复数、下划线分隔）；flyway 履历表 greenfield 直接启用新名 `semgravio_app_registry_flyway_schema_history`（无改名风险）。旧 `OpenApi` 相关（包 `domain/apikey`、错误码 `OpenApiMessage`、HTTP `/api/openapi/...`、bounded context 名）一次性改掉。

## 结果（Consequences）

- 正向：ApiKey 与 SsoClient 独立演进；凭证可完全轮换；有与凭证解耦的稳定身份；与 `cartisan-openapi` 契约不冲突。
- 代价：多一个 `app_code` 概念与不可变纪律。**Greenfield**：弃用旧 V1–V3 迁移与 `oas_api_keys`（V2 明文 admin secret 安全债一并清除），新 V1 起建三表，**无数据迁移**。
- **`callerAppId` 不保证长期稳定**（`api_key` 可轮换）；下游做稳定归属必须用 `app_code`（解析 `api_key → app_code` 或直接以 `app_code` 落库）。
- 偏离架构原文「一张 apps 表、两凭证列可空」（那是 1:1 内联设想）→ 改为三表 normalized；待回灌架构仓库。

## 备选方案（Alternatives considered）

- **单表 + 凭证作 `@Embeddable` 值对象**：否。ApiKey 与 SsoClient 无跨边界不变式、需独立寻址（按 `api_key`/`client_id`）、1:N 扩展困难；大聚合反模式。
- **三聚合根但 ApiKey 1:N（多 apiKey）**：否。零停机轮换可用影子列解决；多 key 引入 per-key 治理层，当前无需求。
- **不引入 `app_code`，用 `api_key` 当稳定引用**：否。凭证 id 当业务引用键是反模式；`api_key` 可完全轮换会断引用。
- **`app_code` 用不透明 id（Stripe 风）**：未选。可读 slug（GCP/GitHub 风）对配置/日志/URL 更友好。

## 开放问题（待后续 ADR）

- 凭证零停机轮换的重叠机制（prev-secret 影子列）——待 secret 存储讨论确认。
- `app_code` 校验规则（字符集、长度、唯一性作用域）。
