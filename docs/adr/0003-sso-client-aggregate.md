# ADR-0003：SsoClient 聚合

- **状态**：Accepted
- **日期**：2026-07-29
- **关联**：ADR-0001（聚合边界与标识模型）、ADR-0002（ApiKey 聚合）；架构仓库 `app-registry.md`、`architecture.md` §4/§6.1；本项目 `CONTEXT.md`「SsoClient」「bootstrap 端点加固」

## 背景（Context）

SsoClient 供 identity（IdP）OIDC：登记「哪些应用能走 SSO」+ 持其 client 元数据。服务从 `semgravio-openapi` 升级为 `app-registry` 的驱动原因即此。

约束：
- 架构不变式：`client_secret` 必须 **hash-only**（像密码，IdP 本地比对、永不返回明文）；与 `apiSecret`（可取回）**存储相反**；SSO 走独立端点、identity 直接消费；`ApiKeyInfo`/`ApiKeyProvider` 绝不服务 SSO；SSO bootstrap 端点需验签（`@RequireSignature`）、必须加固；本服务不做 OIDC token 签发/登录流。
- 安全约束（用户）：暂不引入 KMS/Vault；不过度设计。
- 边界声明（用户）：**所有 SSO 职责归 identity，app-registry 只提供数据**。
- 现状：identity 尚未建，在等 app-registry 落库 + 提供获取端点；app-registry 先行。

## 决策（Decision）

1. **聚合根 + 表 `ar_sso_clients`**（1:1 on `RegisteredApp`，可空）：列 `id`(Tsid) / `app_id`(FK) / `client_id` / `client_secret`(hash) / `redirect_uris`(JSONB array) / `scopes`(JSONB array) / `grants`(JSONB array) / `status` / 审计 + 软删。

2. **`client_secret` = argon2 hash-only**：DB 只存 hash；**永不返回明文**（与 `apiSecret` 可逆加密相反——SSO 比对用 hash，无需取回明文）。

3. **`client_secret` 由 app-registry 生成**（登记处发凭证，与 ApiKey 对称）：创建/轮换 SsoClient 时生成明文 + hash，存 hash、**明文返应用方一次**（应用拿去配 OIDC client），之后永不返回（GitHub PAT / 初始密码模式）。
   - "生成"是登记动作（app-registry 职责）；"比对/token/登录"是 SSO 流程（identity 职责）——边界清晰，坐实不变式 6。

4. **identity 消费契约**：`GET /api/app-registry/sso-clients/{clientId}` 返 client 元数据（含 `client_secret` 的 **hash**、不含明文）。identity 拉取 + 缓存 + 本地 hash 比对（`client_secret_post`：应用发明文 client_secret、identity 比对 hash）。
   - 与 ApiKey bootstrap 端点消费模式对称（拉取 + 缓存 + 本地验证）；差异仅"返 hash 不返明文"（hash-only 不变式使然）。
   - **不做比对端点**——比对是 SSO 职责，归 identity。

5. **前置假设（hash-only ⟹ `client_secret_post`）**：`client_secret` hash-only 排除 `client_secret_jwt`（需明文做 HMAC 验签），故隐含 `client_secret_post`。这不是 app-registry 的新选择，是不变式的推论；identity 建时自然落在 `client_secret_post`，两边统一于同一不变式。

6. **`client_id` 用 SecureRandom 生成**；全局唯一（含软删行）；~~可完全轮换~~ → **已修订（ADR-0005）为终身稳定**：创建时生成一次、不再轮换，轮换只换 `client_secret`。撞名检测同 `app_code`/`api_key`（native query 先查 + `DomainException`，DB 唯一约束并发兜底 → 409）。

7. **不设 `client_secret_prev_hash`**：零停机轮换重叠先不做（YAGNI，与 ApiKey 一致）；留作未来。

8. **列表/Set 用 JSONB 列**（`redirect_uris` / `scopes` / `grants`）：应用不多，不建关联表。

9. **加固 = 验签 + 网络隔离**（`@RequireSignature`）：与 ApiKey bootstrap 的 `@NoSignature` 逃生舱相反，SSO bootstrap 端点**需验签**——identity 作为平台核心服务可预先持有签名凭证、无死锁；另靠"只对内可达"加固，不做 bootstrap token / mTLS。

10. **字段集先按 OIDC 标准最小集**：`client_name` 复用 `RegisteredApp.name`（不单存）；`token_endpoint_auth_method`（默认 `client_secret_post`）/ token TTL 等先不留——identity 建时真需要再加列（greenfield 加列成本低）。**已加首列（ADR-0005）：`post_logout_redirect_uris`**（identity RP-Initiated Logout 独立白名单）；同次重构把管理流拆为「凭证接口 + 配置 PUT」两个独立原语、`client_id` 改终身稳定。

## 结果（Consequences）

- 正向：SsoClient 与 ApiKey 解耦、存储相反（hash vs 可逆加密）各得其所；与架构不变式全部对齐；app-registry 先行落库 + 提供端点，identity 后建直接消费。
- 代价：identity 必须落在 `client_secret_post`（若要 jwt 则回退设计）；hash 进 identity 缓存（可信内部服务，可接受）。
- 残余风险（已接受）：`client_secret` 明文仅在"创建/轮换那一刻"出现于响应 + 应用方配置；hash 在 identity 缓存。均由网络隔离 + argon2 + 高熵 secret 控制。

## 备选方案（Alternatives considered）

- **`client_secret` 比对端点（选 B）**：否。比对是 SSO 流程职责，归 identity；app-registry 只提供数据（用户边界声明）。
- **`client_secret` 由 identity 生成（选项 2）**：否。生成凭证是登记动作，归 app-registry（与 ApiKey 对称、一套管理流）；app-registry 纯被动存储会割裂凭证生命周期。
- **`client_secret` hash 用 bcrypt**：未选。argon2 抗 GPU 更优；除非 identity/平台已有 bcrypt 偏好。
- **`client_secret_prev_hash`（零停机轮换）**：未选。YAGNI，与 ApiKey 一致。
- **`client_secret_jwt`**：否。与 hash-only 不变式冲突（需明文验签）；除非回架构重谈不变式、改可逆加密存。
- **关联表存 redirect_uris/scopes/grants**：未选。应用不多，JSONB 列够用（YAGNI）。

## 开放问题（待后续 ADR）

- `client_id` 的具体格式/长度（32-char Base64URL？与 `api_key` 同款？）。
- SsoClient 创建/轮换的管理端点契约（POST/PUT 形态、明文 secret 一次性返回的响应结构）。
- identity 消费端点的鉴权细节（缓存 TTL 是否与 ApiKey `RemoteApiKeyProvider` 对齐）。
