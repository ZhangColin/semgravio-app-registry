package com.semgravio.appregistry.application.dto.response;

import com.semgravio.appregistry.domain.app.enums.RegisteredAppStatus;

import java.time.LocalDateTime;

/**
 * 应用响应 DTO。
 *
 * @param id          应用内部 id
 * @param appCode     稳定公开 slug
 * @param name        应用名
 * @param description 描述
 * @param status      状态枚举（序列化为 Integer code）
 * @param statusName  状态显示名
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record AppResponse(
        Long id,
        String appCode,
        String name,
        String description,
        RegisteredAppStatus status,
        String statusName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
