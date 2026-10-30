package com.semgravio.appregistry.domain.sso.repository;

import com.semgravio.appregistry.domain.sso.aggregate.SsoClient;
import com.cartisan.data.jpa.repository.BaseRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * {@link SsoClient} 仓库。
 *
 * @since 0.1.0
 */
public interface SsoClientRepository extends BaseRepository<SsoClient, Long> {

    /**
     * 按 {@code client_id} 查活跃 SsoClient（@SQLRestriction 过滤软删行）。
     * 供 bootstrap 端点解析 client 元数据。
     */
    Optional<SsoClient> findByClientId(String clientId);

    /**
     * 按所属 app id 查活跃 SsoClient（@SQLRestriction 过滤软删行）。
     * 供 create-or-rotate（find 现有 / 查询 / 禁用 / 启用）。
     */
    Optional<SsoClient> findByAppId(Long appId);

    /**
     * {@code client_id} 是否存在（含软删行）——native query 绕过 @SQLRestriction。
     * 生成随机 client_id 后的撞名检查（碰撞概率可忽略，作安全网）。
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM ar_sso_clients WHERE client_id = :clientId)", nativeQuery = true)
    boolean existsByClientId(@Param("clientId") String clientId);
}
