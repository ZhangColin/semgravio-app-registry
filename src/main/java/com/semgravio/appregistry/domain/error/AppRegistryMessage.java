package com.semgravio.appregistry.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * AppRegistry 上下文错误码。
 *
 * <p>本上下文（应用登记处）跨 app / api-key / sso-client 三个聚合的领域错误码集中于此，
 * 随各 ticket 逐步扩充：
 * <ul>
 *   <li>#3 app：app_code 撞名、状态转换冲突等（撞名主路径走
 *       {@link com.cartisan.core.exception.BaseCodeMessage#DUPLICATE}）</li>
 *   <li>#4 api-key：api_key 撞名、ApiKey 缺失等</li>
 *   <li>#5 sso-client：client_id 撞名、SsoClient 缺失等</li>
 * </ul>
 *
 * <p>通用语义优先复用 {@link com.cartisan.core.exception.BaseCodeMessage}：
 * 资源不存在用 {@code RESOURCE_NOT_FOUND}、重复冲突用 {@code DUPLICATE}；
 * 本枚举只承载领域专属、语义更精确的错误码。
 *
 * @since 0.1.0
 */
public enum AppRegistryMessage implements CodeMessage {

    // ===== App（RegisteredApp 聚合，#3）=====
    /** app_code 格式不正确（{0}=输入值）。*/
    APP_CODE_INVALID(400, "AR_APP_CODE_INVALID", "应用编码格式不正确: {0}"),
    /** 应用名为空。*/
    APP_NAME_REQUIRED(400, "AR_APP_NAME_REQUIRED", "应用名称不能为空"),
    /** 应用已禁用，无法再次禁用（{0}=appCode）。*/
    APP_ALREADY_DISABLED(409, "AR_APP_ALREADY_DISABLED", "应用已处于禁用状态: {0}"),
    /** 应用已启用，无法再次启用（{0}=appCode）。*/
    APP_ALREADY_ENABLED(409, "AR_APP_ALREADY_ENABLED", "应用已处于启用状态: {0}"),

    // ===== ApiKey（签名凭证聚合，#4）=====
    /** 签名凭证已禁用，无法再次禁用。*/
    API_KEY_ALREADY_DISABLED(409, "AR_API_KEY_ALREADY_DISABLED", "签名凭证已处于禁用状态"),
    /** 签名凭证已启用，无法再次启用。*/
    API_KEY_ALREADY_ENABLED(409, "AR_API_KEY_ALREADY_ENABLED", "签名凭证已处于启用状态"),

    // ===== Platform seed（平台预置应用，#6）=====
    /** 平台预置应用不可禁用（{0}=appCode）。*/
    ADMIN_CONSOLE_CANNOT_DISABLE(403, "AR_ADMIN_CONSOLE_CANNOT_DISABLE", "平台预置应用不可禁用: {0}"),

    // ===== SsoClient（SSO 凭证聚合，#5）=====
    /** SSO 回调地址为空（至少一个 redirect_uri）。OIDC 标准字段 redirect_uris。*/
    SSO_REDIRECT_URI_REQUIRED(400, "AR_SSO_REDIRECT_URI_REQUIRED", "回调地址不能为空"),
    /** SSO 登出回跳地址为空（至少一个 post_logout_redirect_uri）。OIDC RP-Initiated Logout 白名单（ADR-0005）。*/
    SSO_POST_LOGOUT_REDIRECT_URI_REQUIRED(400, "AR_SSO_POST_LOGOUT_REDIRECT_URI_REQUIRED", "登出回跳地址不能为空"),
    /** SSO 凭证已禁用，无法再次禁用。*/
    SSO_CLIENT_ALREADY_DISABLED(409, "AR_SSO_CLIENT_ALREADY_DISABLED", "SSO 凭证已处于禁用状态"),
    /** SSO 凭证已启用，无法再次启用。*/
    SSO_CLIENT_ALREADY_ENABLED(409, "AR_SSO_CLIENT_ALREADY_ENABLED", "SSO 凭证已处于启用状态"),

    ;

    private final int httpStatus;
    private final String code;
    private final String message;

    AppRegistryMessage(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return this.code;
    }

    @Override
    public String message() {
        return this.message;
    }

    @Override
    public int httpStatus() {
        return this.httpStatus;
    }
}
