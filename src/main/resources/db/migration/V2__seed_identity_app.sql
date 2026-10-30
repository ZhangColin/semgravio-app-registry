-- V2: seed 平台应用 identity（app_code = identity）。
-- 为 semgravio-identity 消费 app-registry 的 SSO bootstrap 端点（@RequireSignature）提供应用登记项：
-- identity 调该端点需以自身 ApiKey 签名，故需先在 app-registry 登记为应用（GitHub #17）。
--
-- 范围：只插应用主行（ar_registered_apps）。ApiKey facet 由维护者事后通过既有 createOrRotate 接口手动创建
-- （接口负责生成 + AES-GCM 加密 + 响应一次明文，明文灌进 identity prod 配置）。
-- identity 是 IdP、非 SSO 消费方，故不插 SsoClient。
--
-- id：冻结一个由框架 TsidGenerator 生成的真实 TSID（脚本编写时一次性产出、跨环境固定），与运行时 TSID 同算法；
-- 与 payment（V3）及今后所有平台 seed 统一策略，碰撞概率可忽略（同毫秒同 22 位随机 ≈ 1/4M）。
-- ON CONFLICT (app_code) DO NOTHING：即便某环境已手动建过 identity 也安全、不阻断迁移（幂等）。
INSERT INTO ar_registered_apps (id, app_code, name, description, status, created_at, updated_at, deleted)
VALUES (345046189529202976,
        'identity',
        '身份服务',
        '平台终端用户的集中式身份基座（IdP）：账户与凭据、注册登录、SSO（OIDC）、社交登录归一，供所有业务应用消费。',
        1,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP,
        FALSE)
ON CONFLICT (app_code) DO NOTHING;
