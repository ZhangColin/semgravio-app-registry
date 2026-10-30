package com.semgravio.appregistry.application.dto.response;

import com.semgravio.appregistry.domain.sso.enums.SsoClientStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * SsoClient 凭证接口响应（创建 / 重置 {@code client_secret}）——<strong>一次性</strong>返回明文 {@code client_secret}。
 *
 * <p>消费方必须在此次响应里捕获并妥善保管 {@code client_secret}：之后任何接口都不可再取回明文
 * （DB 只存 argon2 hash，不可逆；管理 GET 仅返 {@link SsoClientResponse}）。{@code client_id} 终身稳定——
 * 重置只换 {@code client_secret}；创建时配置（{@code redirectUris} 等）为空，由配置 PUT 单独编排（ADR-0005）。</p>
 *
 * @param id            SsoClient id
 * @param appId         所属应用 id
 * @param clientId      OIDC client_id（终身稳定）
 * @param clientSecret  明文 secret（仅此一次返回）
 * @param redirectUris  回调地址列表（创建时为空，配置 PUT 后填充）
 * @param postLogoutRedirectUris 登出回跳白名单（OIDC RP-Initiated Logout；创建时为空）
 * @param scopes        授权范围（创建时为空）
 * @param grants        授权类型（创建时为空）
 * @param status        凭证状态
 * @param statusName    状态中文名
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 *
 * @since 0.1.0
 */
public record SsoClientCreatedResponse(
        Long id,
        Long appId,
        String clientId,
        String clientSecret,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        Set<String> scopes,
        Set<String> grants,
        SsoClientStatus status,
        String statusName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
