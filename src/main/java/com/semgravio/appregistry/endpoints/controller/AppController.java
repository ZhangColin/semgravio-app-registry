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
 * <p><b>错误码契约</b>（#25，对齐 identity #77 口径）：每端点 {@code @ErrorCodes} 声明其调用链
 * 可抛的业务错误码（渲染进 swagger description，与 CodeMessage 枚举单点同源）；声明序 = 调用链发生序。
 * 验签 401 与参数校验系框架统一响应，不逐端点声明；DTO {@code @Valid} 与聚合侧同义校验
 * 按并集列（防两套演进漂移后契约缺码）。</p>
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
    @ErrorCodes({"DUPLICATE", "AR_APP_CODE_INVALID", "AR_APP_NAME_REQUIRED"})
    public ApiResponse<AppResponse> create(@Valid @RequestBody CreateAppCommand command) {
        return ApiResponse.ok(appService.create(command));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询应用详情")
    @ErrorCodes("RESOURCE_NOT_FOUND")
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
    @ErrorCodes({"RESOURCE_NOT_FOUND", "AR_ADMIN_CONSOLE_CANNOT_DISABLE", "AR_APP_ALREADY_DISABLED"})
    public ApiResponse<AppResponse> disable(@PathVariable Long id) {
        return ApiResponse.ok(appService.disable(id));
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用应用")
    @ErrorCodes({"RESOURCE_NOT_FOUND", "AR_APP_ALREADY_ENABLED"})
    public ApiResponse<AppResponse> enable(@PathVariable Long id) {
        return ApiResponse.ok(appService.enable(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新应用信息")
    @ErrorCodes({"RESOURCE_NOT_FOUND", "AR_APP_NAME_REQUIRED"})
    public ApiResponse<AppResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody UpdateAppCommand command) {
        return ApiResponse.ok(appService.update(id, command));
    }
}
