-- V3: seed 平台应用 payment（app_code = payment）。
-- 为 semgravio-payment 作为签名 facet 消费方登记应用主行：payment 机机调用平台各服务需以自身 ApiKey 签名，
-- 各服务 LocalApiKeyProvider/RemoteApiKeyProvider 凭 api_key/appId 验签，故需先在 app-registry 登记。
--
-- 范围：只插应用主行（ar_registered_apps）。ApiKey facet 由维护者事后通过既有 createOrRotate 接口手动创建
-- （接口负责生成 + AES-GCM 加密 + 响应一次明文，明文灌进 payment prod 配置）。
-- payment 是后端支付域、非 SSO 消费方（无用户登录流），故不插 SsoClient。
--
-- id：冻结一个由框架 TsidGenerator 生成的真实 TSID（脚本编写时一次性产出、跨环境固定），与运行时 TSID 同算法；
-- 与 identity（V2）及今后所有平台 seed 统一策略，碰撞概率可忽略（同毫秒同 22 位随机 ≈ 1/4M）。
-- ON CONFLICT (app_code) DO NOTHING：即便某环境已手动建过 payment 也安全、不阻断迁移（幂等）。
INSERT INTO ar_registered_apps (id, app_code, name, description, status, created_at, updated_at, deleted)
VALUES (345042853334400704,
        'payment',
        '支付服务',
        '平台支付域：对接外部支付通道（现 ICBC 单通道），提供下单/退款/回调能力，驱动钱包入账与冲账，供业务应用消费。',
        1,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP,
        FALSE)
ON CONFLICT (app_code) DO NOTHING;
