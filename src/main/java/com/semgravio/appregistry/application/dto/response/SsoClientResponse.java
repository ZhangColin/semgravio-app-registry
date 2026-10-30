package com.semgravio.appregistry.application.dto.response;

import com.semgravio.appregistry.domain.sso.enums.SsoClientStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * SsoClient 查询 / 配置 PUT / 禁用 / 启用响应——<strong>不含</strong> {@code client_secret}（明文仅凭证接口
 * 返一次，见 {@link SsoClientCreatedResponse}；hash 仅 bootstrap 端点按需返）。
 *
 * @param id           SsoClient id
 * @param appId        所属应用 id
 * @param clientId     OIDC client_id
 * @param redirectUris 回调地址列表
 * @param postLogoutRedirectUris 登出回跳白名单（OIDC RP-Initiated Logout）
 * @param scopes       授权范围
 * @param grants       授权类型
 * @param status       凭证状态
 * @param statusName   状态中文名
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 *
 * @since 0.1.0
 */
public record SsoClientResponse(
        Long id,
        Long appId,
        String clientId,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        Set<String> scopes,
        Set<String> grants,
        SsoClientStatus status,
        String statusName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
