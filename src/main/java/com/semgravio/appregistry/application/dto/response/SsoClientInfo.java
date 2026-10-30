package com.semgravio.appregistry.application.dto.response;

import java.util.List;
import java.util.Set;

/**
 * SsoClient <strong>bootstrap 契约</strong>——identity（IdP）拉取消费的 client 元数据。
 *
 * <p>与 ApiKey 的 {@code ApiKeyInfo} 消费模式对称（拉取 + 缓存 + 本地验证）；差异仅"返 hash 不返明文"
 * （hash-only 不变式使然，ADR-0003 §4）。identity 拿到后：缓存 + 对应用发来的明文 client_secret 做
 * {@code client_secret_post} 比对（用 hash）。</p>
 *
 * <p>{@code active=false}（app/client 任一禁用）时 {@code clientSecretHash=null}——不返有效元数据，
 * identity 见 active=false 即拒为该 client 办 SSO。</p>
 *
 * @param clientId        OIDC client_id
 * @param appId           所属应用 id
 * @param clientName      client 名（复用 RegisteredApp.name，不单存）
 * @param clientSecretHash client_secret 的 argon2 hash（仅 active 时返；否则 null）
 * @param redirectUris    回调地址列表
 * @param postLogoutRedirectUris 登出回跳白名单（OIDC RP-Initiated Logout）
 * @param scopes          授权范围
 * @param grants          授权类型
 * @param active          组合生效 = client.active && app.active
 *
 * @since 0.1.0
 */
public record SsoClientInfo(
        String clientId,
        Long appId,
        String clientName,
        String clientSecretHash,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        Set<String> scopes,
        Set<String> grants,
        boolean active) {
}
