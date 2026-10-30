package com.semgravio.appregistry.domain.app.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 应用状态。
 *
 * <ul>
 *   <li>{@link #ACTIVE} - 启用：应用可正常使用其凭证。</li>
 *   <li>{@link #DISABLED} - 禁用：查询凭证时 join app 校验，app 禁用则凭证失效（ADR-0001 §5）。</li>
 * </ul>
 *
 * @since 0.1.0
 */
public enum RegisteredAppStatus implements BaseEnum<RegisteredAppStatus> {

    DISABLED(0, "禁用"),
    ACTIVE(1, "启用");

    private final Integer code;
    private final String name;

    RegisteredAppStatus(Integer code, String name) {
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
     * JPA Converter - 自动应用到所有 {@link RegisteredAppStatus} 字段（实体零注解）。
     * <p>内部类名用 {@code JpaConverter} 而非 {@code Converter}，避免与 {@link Converter} 注解冲突。</p>
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<RegisteredAppStatus> {
        public JpaConverter() {
            super(RegisteredAppStatus.class);
        }
    }
}
