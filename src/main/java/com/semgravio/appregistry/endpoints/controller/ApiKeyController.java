package com.semgravio.appregistry.endpoints.controller;

import com.semgravio.appregistry.application.ApiKeyAppService;
import com.semgravio.appregistry.application.dto.response.ApiKeyCreatedResponse;
import com.semgravio.appregistry.application.dto.response.ApiKeyResponse;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ApiKey 管理 REST API（挂在应用路径下，1:1）。
 *
 * <p>加固说明：app-registry 只对内可达，网络边界即信任边界（ADR-0002 §9）。</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/app-registry/apps/{appId}/api-keys")
@RequireSignature
@Tag(name = "ApiKeys", description = "ApiKey 管理（机机验签凭证）")
public class ApiKeyController {

    private final ApiKeyAppService apiKeyAppService;

    public ApiKeyController(ApiKeyAppService apiKeyAppService) {
        this.apiKeyAppService = apiKeyAppService;
    }

    @PostMapping
    @Operation(summary = "创建或轮换 ApiKey", description = "已有则轮换、否则新建；响应一次性返回明文 apiSecret")
    public ApiResponse<ApiKeyCreatedResponse> createOrRotate(@PathVariable Long appId) {
        return ApiResponse.ok(apiKeyAppService.createOrRotate(appId));
    }

    @GetMapping
    @Operation(summary = "查询应用的 ApiKey", description = "不返回明文 apiSecret")
    public ApiResponse<ApiKeyResponse> get(@PathVariable Long appId) {
        return ApiResponse.ok(apiKeyAppService.getByAppId(appId));
    }

    @PutMapping("/disable")
    @Operation(summary = "禁用 ApiKey")
    public ApiResponse<ApiKeyResponse> disable(@PathVariable Long appId) {
        return ApiResponse.ok(apiKeyAppService.disable(appId));
    }

    @PutMapping("/enable")
    @Operation(summary = "启用 ApiKey")
    public ApiResponse<ApiKeyResponse> enable(@PathVariable Long appId) {
        return ApiResponse.ok(apiKeyAppService.enable(appId));
    }
}
