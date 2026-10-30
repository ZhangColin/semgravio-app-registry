package com.semgravio.appregistry.domain.sso.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * SsoClient 状态。
 *
 * <p>组合生效状态由 {@code SsoClient.status && RegisteredApp.status} 联合决定（app 禁用则凭证失效），
 * 见 {@code SsoClientAppService.resolveSsoClientInfo} / bootstrap 端点的级联逻辑。</p>
 *
 * @since 0.1.0
 */
public enum SsoClientStatus implements BaseEnum<SsoClientStatus> {

    /** 禁用。*/
    DISABLED(0, "禁用"),
    /** 启用。*/
    ACTIVE(1, "启用");

    private final int code;
    private final String name;

    SsoClientStatus(int code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * JPA Converter - 自动应用到所有 {@link SsoClientStatus} 字段（实体零注解）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<SsoClientStatus> {
        public JpaConverter() {
            super(SsoClientStatus.class);
        }
    }
}
