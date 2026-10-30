package com.semgravio.appregistry.application.dto.query;

import com.semgravio.appregistry.domain.app.enums.RegisteredAppStatus;
import com.cartisan.data.jpa.specification.Condition;
import com.cartisan.data.jpa.specification.ConditionType;

/**
 * 应用列表查询参数。
 *
 * <p>{@code keyword} 通过 {@code blurry} 对 {@code appCode} 和 {@code name} 做 OR 模糊匹配；
 * {@code status} 按状态精确筛选，不传则不过滤。{@code @Condition} 自动跳过 null 值。</p>
 *
 * @since 0.1.0
 */
public record AppQuery(
        @Condition(blurry = "appCode,name", type = ConditionType.INNER_LIKE)
        String keyword,

        @Condition(type = ConditionType.EQUAL)
        RegisteredAppStatus status
) {
}
