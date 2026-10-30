package com.semgravio.appregistry.application.dto.response;

import com.semgravio.appregistry.domain.signature.enums.ApiKeyStatus;

import java.time.LocalDateTime;

/**
 * ApiKey 查询/禁用/启用响应——<strong>不含</strong> {@code apiSecret} 明文
 * （明文仅创建/轮换时返一次，见 {@link ApiKeyCreatedResponse}）。
 *
 * @param id         ApiKey id
 * @param appId      所属应用 id
 * @param apiKey     凭证标识（= 框架 X-App-Id）
 * @param status     凭证状态
 * @param statusName 状态中文名
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 *
 * @since 0.1.0
 */
public record ApiKeyResponse(
        Long id,
        Long appId,
        String apiKey,
        ApiKeyStatus status,
        String statusName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
