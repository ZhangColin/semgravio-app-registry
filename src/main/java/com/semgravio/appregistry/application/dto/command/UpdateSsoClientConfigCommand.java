package com.semgravio.appregistry.application.dto.command;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/**
 * 更新 SsoClient 配置命令（配置 PUT 专用）——整份替换 {@code redirect_uris} /
 * {@code post_logout_redirect_uris} / {@code scopes} / {@code grants}，<strong>不动凭证、不动 status</strong>。
 *
 * <p>凭证操作（创建 / 重置 {@code client_secret}）走独立「凭证接口」、无请求体（ADR-0005：配置与凭证职责分离）。
 * 故本命令只承载配置四字段，{@code @NotEmpty} 仅在此接口生效（凭证接口不校验配置）。</p>
 *
 * @param redirectUris         回调地址列表（至少一个）；OIDC redirect_uri
 * @param postLogoutRedirectUris 登出回跳白名单（至少一个）；OIDC RP-Initiated Logout post_logout_redirect_uri
 * @param scopes               授权范围（可空 → 空 set）
 * @param grants               授权类型（可空 → 空 set）
 */
public record UpdateSsoClientConfigCommand(

        @NotEmpty(message = "回调地址不能为空")
        List<@Size(max = 512) String> redirectUris,

        @NotEmpty(message = "登出回跳地址不能为空")
        List<@Size(max = 512) String> postLogoutRedirectUris,

        Set<String> scopes,

        Set<String> grants
) {
}
