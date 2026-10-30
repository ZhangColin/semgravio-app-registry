package com.semgravio.appregistry.endpoints.controller;

import com.semgravio.appregistry.application.SsoClientAppService;
import com.semgravio.appregistry.application.dto.command.UpdateSsoClientConfigCommand;
import com.semgravio.appregistry.application.dto.response.SsoClientCreatedResponse;
import com.semgravio.appregistry.application.dto.response.SsoClientResponse;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SsoClient 管理 REST API（挂在应用路径下，1:1）。配置与凭证职责分离（ADR-0005）——两个独立原语，调用端自由编排：
 * <ul>
 *   <li>{@code POST .../sso-clients/credentials}：创建（无 SsoClient）/ 重置 {@code client_secret}（已存在，
 *       {@code client_id} 终身稳定）；无请求体，响应一次性返明文 {@code client_secret}。</li>
 *   <li>{@code PUT .../sso-clients}：整份替换配置（{@code redirect_uris} / {@code post_logout_redirect_uris} /
 *       {@code scopes} / {@code grants}）；不动凭证、不动 status；SsoClient 不存在 → 404。</li>
 * </ul>
 *
 * <p>加固说明：app-registry 只对内可达，网络边界即信任边界（ADR-0003 §9）。</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/app-registry/apps/{appId}/sso-clients")
@RequireSignature
@Tag(name = "SsoClients", description = "SsoClient 管理（OIDC client 元数据）")
public class SsoClientController {

    private final SsoClientAppService ssoClientAppService;

    public SsoClientController(SsoClientAppService ssoClientAppService) {
        this.ssoClientAppService = ssoClientAppService;
    }

    @PostMapping("/credentials")
    @Operation(summary = "创建或重置 SsoClient 凭证",
            description = "无 SsoClient 则创建（生成 client_id + client_secret），否则仅重置 client_secret（client_id 终身稳定）；"
                    + "响应一次性返回明文 client_secret。无请求体、不校验配置。")
    public ApiResponse<SsoClientCreatedResponse> credentials(@PathVariable Long appId) {
        return ApiResponse.ok(ssoClientAppService.generateOrResetCredentials(appId));
    }

    @PutMapping
    @Operation(summary = "更新 SsoClient 配置",
            description = "整份替换 redirect_uris / post_logout_redirect_uris / scopes / grants；不动凭证、不动 status；"
                    + "SsoClient 不存在返回 404。")
    public ApiResponse<SsoClientResponse> updateConfig(
            @PathVariable Long appId, @Valid @RequestBody UpdateSsoClientConfigCommand command) {
        return ApiResponse.ok(ssoClientAppService.updateConfig(appId, command));
    }

    @GetMapping
    @Operation(summary = "查询应用的 SsoClient", description = "不返回明文 client_secret")
    public ApiResponse<SsoClientResponse> get(@PathVariable Long appId) {
        return ApiResponse.ok(ssoClientAppService.getByAppId(appId));
    }

    @PutMapping("/disable")
    @Operation(summary = "禁用 SsoClient")
    public ApiResponse<SsoClientResponse> disable(@PathVariable Long appId) {
        return ApiResponse.ok(ssoClientAppService.disable(appId));
    }

    @PutMapping("/enable")
    @Operation(summary = "启用 SsoClient")
    public ApiResponse<SsoClientResponse> enable(@PathVariable Long appId) {
        return ApiResponse.ok(ssoClientAppService.enable(appId));
    }
}
