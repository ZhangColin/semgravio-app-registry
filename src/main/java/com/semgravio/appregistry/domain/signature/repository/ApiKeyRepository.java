package com.semgravio.appregistry.domain.signature.repository;

import com.semgravio.appregistry.domain.signature.aggregate.ApiKey;
import com.cartisan.data.jpa.repository.BaseRepository;

import java.util.Optional;

/**
 * {@link ApiKey} 仓库。
 *
 * @since 0.1.0
 */
public interface ApiKeyRepository extends BaseRepository<ApiKey, Long> {

    /**
     * 按 {@code api_key} 查活跃 ApiKey（@SQLRestriction 过滤软删行）。
     * 供 LocalApiKeyProvider / bootstrap 端点解析验签信息。
     */
    Optional<ApiKey> findByApiKey(String apiKey);

    /**
     * 按所属 app id 查活跃 ApiKey（@SQLRestriction 过滤软删行）。
     * 供 create-or-rotate（find 现有 / 查询 / 禁用 / 启用）。
     */
    Optional<ApiKey> findByAppId(Long appId);
}
