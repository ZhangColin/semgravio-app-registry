package com.semgravio.appregistry.endpoints.controller;

import com.semgravio.appregistry.application.SsoClientAppService;
import com.semgravio.appregistry.application.dto.response.SsoClientInfo;
import com.cartisan.core.exception.BaseCodeMessage;
import com.cartisan.core.exception.DomainException;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SsoClient <strong>bootstrap 端点</strong>——需验签（identity 作为平台核心服务可预先持有签名凭证，无死锁）。
 *
 * <p>{@code GET /api/app-registry/sso-clients/{clientId}} 返 {@link SsoClientInfo}：active 时含
 * {@code client_secret} 的 <strong>hash</strong>（不含明文，hash-only 不变式）；app/client 任一禁用 →
 * {@code active=false} 且 hash=null（不返有效元数据）。</p>
 *
 * <p><strong>加固 = 网络隔离（只对内可达）</strong>：在 {@code @RequireSignature} 验签之外，不再叠 bootstrap token /
 * mTLS / IP allowlist——app-registry 部署为只对内可达，网络边界即信任边界（ADR-0003 §9）。唯一调用方是可信的 identity 服务。</p>
 *
 * <p>与 ApiKey {@code /api-keys/{apiKey}} 对称：拉取 + 缓存 + 本地验证；差异仅"返 hash 不返明文"。</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/app-registry/sso-clients")
@RequireSignature
@Tag(name = "Bootstrap", description = "SsoClient bootstrap 端点（需验签）")
public class SsoBootstrapController {

    private final SsoClientAppService ssoClientAppService;

    public SsoBootstrapController(SsoClientAppService ssoClientAppService) {
        this.ssoClientAppService = ssoClientAppService;
    }

    @GetMapping("/{clientId}")
    @Operation(summary = "按 client_id 取 SsoClient（bootstrap）",
            description = "返 SsoClientInfo（active 时含 client_secret hash；不含明文）")
    public ApiResponse<SsoClientInfo> getByClientId(@PathVariable String clientId) {
        SsoClientInfo info = ssoClientAppService.resolveSsoClientInfo(clientId)
                .orElseThrow(() -> new DomainException(BaseCodeMessage.RESOURCE_NOT_FOUND, clientId));
        return ApiResponse.ok(info);
    }
}
