package com.semgravio.appregistry.endpoints.controller;

import com.semgravio.appregistry.application.RegisteredAppAppService;
import com.semgravio.appregistry.application.dto.command.CreateAppCommand;
import com.semgravio.appregistry.application.dto.command.UpdateAppCommand;
import com.semgravio.appregistry.application.dto.query.AppQuery;
import com.semgravio.appregistry.application.dto.response.AppResponse;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用登记管理 REST API。
 *
 * <p>加固说明：app-registry 只对内可达，网络边界即信任边界，管理端点不再叠鉴权（ADR-0001/0002 §9）。
 * 签名验签过滤器对无 {@code X-App-Id} 的请求直接放行。</p>
 *
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/app-registry/apps")
@RequireSignature
@Validated
@Tag(name = "Apps", description = "应用登记管理")
public class AppController {

    private final RegisteredAppAppService appService;

    public AppController(RegisteredAppAppService appService) {
        this.appService = appService;
    }

    @PostMapping
    @Operation(summary = "创建应用")
    public ApiResponse<AppResponse> create(@Valid @RequestBody CreateAppCommand command) {
        return ApiResponse.ok(appService.create(command));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询应用详情")
    public ApiResponse<AppResponse> getById(@PathVariable Long id) {
        return ApiResponse.ok(appService.findById(id));
    }

    @GetMapping
    @Operation(summary = "分页查询应用列表",
            description = "分页走框架 Pagination 契约（#24 起全链 1-based）：page 默认 1、size 默认 20"
                    + "（上限 100，越界静默贴边），sort 多值 token（如 sort=createdAt,desc）；不传 sort 默认 createdAt 降序。"
                    + "请求页超出尾页 → 空页（200，请求页码原样回显）。无业务错误码（空页不报错；仅基建错可中断）。")
    @ErrorCodes("INTERNAL_SERVER_ERROR")
    public PageResponse<AppResponse> list(AppQuery query, Pagination pagination) {
        return appService.list(query, pagination);
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "禁用应用")
    public ApiResponse<AppResponse> disable(@PathVariable Long id) {
        return ApiResponse.ok(appService.disable(id));
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用应用")
    public ApiResponse<AppResponse> enable(@PathVariable Long id) {
        return ApiResponse.ok(appService.enable(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新应用信息")
    public ApiResponse<AppResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody UpdateAppCommand command) {
        return ApiResponse.ok(appService.update(id, command));
    }
}
