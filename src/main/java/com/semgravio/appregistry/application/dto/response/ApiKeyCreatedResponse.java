package com.semgravio.appregistry.application.dto.response;

import com.semgravio.appregistry.domain.signature.enums.ApiKeyStatus;

import java.time.LocalDateTime;

/**
 * ApiKey 创建/轮换响应——<strong>一次性</strong>返回明文 {@code apiSecret}。
 *
 * <p>消费方必须在此次响应里捕获并妥善保管 {@code apiSecret}：之后任何接口都不可再取回明文
 * （DB 只存密文；管理 GET 仅返 {@link ApiKeyResponse}）。</p>
 *
 * @param id         ApiKey id
 * @param appId      所属应用 id
 * @param apiKey     凭证标识（= 框架 X-App-Id）
 * @param apiSecret  明文 secret（仅此一次返回）
 * @param status     凭证状态
 * @param statusName 状态中文名
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 *
 * @since 0.1.0
 */
public record ApiKeyCreatedResponse(
        Long id,
        Long appId,
        String apiKey,
        String apiSecret,
        ApiKeyStatus status,
        String statusName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
