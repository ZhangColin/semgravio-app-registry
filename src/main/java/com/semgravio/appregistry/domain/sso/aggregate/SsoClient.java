package com.semgravio.appregistry.domain.sso.aggregate;

import com.semgravio.appregistry.domain.error.AppRegistryMessage;
import com.semgravio.appregistry.domain.sso.enums.SsoClientStatus;
import com.cartisan.core.domain.AggregateRoot;
import com.cartisan.core.stereotype.Aggregate;
import com.cartisan.core.util.Assertions;
import com.cartisan.data.jpa.domain.AuditableSoftDeletable;
import com.cartisan.data.jpa.id.TsidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SsoClient 聚合根——OIDC client 元数据（供 identity IdP）。
 *
 * <p>1:1 挂在 {@code RegisteredApp} 上、可空。{@code client_id} = SecureRandom 生成、全局唯一、<strong>终身稳定</strong>
 * （创建时生成一次，不再轮换；ADR-0005 修订 ADR-0003 §6）；{@code client_secret} 以 <strong>argon2 hash</strong> 入库
 * （hash-only，不可逆、永不返回明文）——与 ApiKey 的 AES-GCM 可逆密文存储<strong>相反</strong>（ADR-0003 §2）。
 * 明文 {@code client_secret} 仅由应用层在创建/重置凭证时生成、哈希、返响应一次。</p>
 *
 * <p>{@code redirect_uris} / {@code post_logout_redirect_uris}（均列表、保序）/ {@code scopes}（去重）/
 * {@code grants}（去重）以 JSONB 列存储，应用不多、不建关联表（ADR-0003 §8）。</p>
 *
 * <h3>职责分离操作模型（ADR-0005）</h3>
 * <p>配置更新与凭证生成/重置是<strong>两个独立原语</strong>，调用端自由编排（服务不强制顺序）。四个操作正交、互不连带：</p>
 * <ul>
 *   <li>{@link #create(Long, String, String)} ——仅凭证：建聚合、置 ACTIVE；配置初始为空（允许「凭证已建、配置未 PUT」中间态）。</li>
 *   <li>{@link #resetCredentials(String)} ——仅 {@code client_secret}：{@code client_id} 终身稳定、status 不变。</li>
 *   <li>{@link #updateConfig(List, List, Set, Set)} ——仅配置：整份替换，两 URI 列表 {@code @NotEmpty}；不动凭证、不动 status。</li>
 *   <li>{@link #disable()} / {@link #enable()} ——仅状态：重复转换抛 409。</li>
 * </ul>
 *
 * <h3>组合生效</h3>
 * <p>本聚合 status 仅表示自身状态；SSO 是否放行由 {@code SsoClient.status && RegisteredApp.status}
 * 联合决定（app 禁用则凭证失效）——该级联在 {@code SsoClientAppService}/bootstrap 端点查询时 join app 计算，
 * 不在本聚合。</p>
 *
 * @since 0.1.0
 */
@Entity
@Table(name = "ar_sso_clients")
@Aggregate
// DATA-004：@SQLRestriction 在 @MappedSuperclass 上继承不可靠，实体类重复声明确保软删过滤生效。
@SQLRestriction("deleted = false")
@Getter
public class SsoClient extends AuditableSoftDeletable implements AggregateRoot<SsoClient, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_id", nullable = false, updatable = false)
    private Long appId;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    /** argon2 hash（自描述字符串，含算法参数 + salt）。明文绝不落库。*/
    @Column(name = "client_secret", nullable = false, length = 1024)
    private String clientSecret;

    /** OIDC 回调地址（保序、可重复，jsonb array）。*/
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redirect_uris", nullable = false)
    private List<String> redirectUris = new ArrayList<>();

    /** OIDC RP-Initiated Logout 登出回跳白名单（保序、可重复，jsonb array）；与 redirect_uris 平级独立（ADR-0005）。*/
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "post_logout_redirect_uris", nullable = false)
    private List<String> postLogoutRedirectUris = new ArrayList<>();

    /** 授权范围（去重，jsonb array）。*/
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scopes", nullable = false)
    private Set<String> scopes = new LinkedHashSet<>();

    /** 授权类型（去重，jsonb array）。*/
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grants", nullable = false)
    private Set<String> grants = new LinkedHashSet<>();

    @Column(name = "status", nullable = false)
    private SsoClientStatus status;

    protected SsoClient() {
    }

    private SsoClient(Long appId, String clientId, String hashedSecret) {
        this.id = TsidGenerator.newInstance().generate();
        this.appId = appId;
        this.clientId = clientId;
        this.clientSecret = hashedSecret;
        // 配置初始为空——凭证接口不校验配置（ADR-0005：允许「凭证已建、配置未 PUT」中间态）。
        this.status = SsoClientStatus.ACTIVE;
    }

    /**
     * 创建 SsoClient（工厂，仅凭证）。{@code client_id}/hash 由应用层生成（SecureRandom + argon2）后传入；
     * 配置初始为空，由 {@link #updateConfig} 单独编排。
     *
     * @param appId        所属应用 id
     * @param clientId     OIDC client_id（SecureRandom 生成，终身稳定）
     * @param hashedSecret client_secret 的 argon2 hash
     * @return 新建的、尚未持久化的 SsoClient（ACTIVE、空配置）
     */
    public static SsoClient create(Long appId, String clientId, String hashedSecret) {
        // 入参由应用层生成（SecureRandom + argon2），按构造保证非空，无需领域断言。
        return new SsoClient(appId, clientId, hashedSecret);
    }

    /**
     * 重置凭证：仅换 {@code client_secret} 的 hash。{@code client_id} 终身稳定（ADR-0005）、status 不变
     * （凭证操作与启停正交——要复活已禁用 client 走 {@link #enable()}）。
     *
     * @param newHashedSecret 新 client_secret 的 argon2 hash（应用层先生成明文 + 哈希再传入）
     */
    public void resetCredentials(String newHashedSecret) {
        this.clientSecret = newHashedSecret;
    }

    /**
     * 整份替换配置（{@code redirect_uris} / {@code post_logout_redirect_uris} / {@code scopes} / {@code grants}）。
     * 两 URI 列表至少一个（{@code @NotEmpty}，OIDC 回调 + 登出回跳白名单）；<strong>不动凭证、不动 status</strong>。
     *
     * @param redirectUris         回调地址列表（至少一个）
     * @param postLogoutRedirectUris 登出回跳白名单（至少一个，OIDC RP-Initiated Logout）
     * @param scopes               授权范围（可空 → 空 set）
     * @param grants               授权类型（可空 → 空 set）
     */
    public void updateConfig(List<String> redirectUris, List<String> postLogoutRedirectUris,
                             Set<String> scopes, Set<String> grants) {
        Assertions.require(redirectUris != null && !redirectUris.isEmpty(),
                AppRegistryMessage.SSO_REDIRECT_URI_REQUIRED);
        Assertions.require(postLogoutRedirectUris != null && !postLogoutRedirectUris.isEmpty(),
                AppRegistryMessage.SSO_POST_LOGOUT_REDIRECT_URI_REQUIRED);
        this.redirectUris = new ArrayList<>(redirectUris);
        this.postLogoutRedirectUris = new ArrayList<>(postLogoutRedirectUris);
        this.scopes = new LinkedHashSet<>(scopes == null ? Set.of() : scopes);
        this.grants = new LinkedHashSet<>(grants == null ? Set.of() : grants);
    }

    /**
     * 禁用 SsoClient。仅 ACTIVE 可禁用，重复禁用抛 409。
     */
    public void disable() {
        Assertions.require(status == SsoClientStatus.ACTIVE, AppRegistryMessage.SSO_CLIENT_ALREADY_DISABLED);
        this.status = SsoClientStatus.DISABLED;
    }

    /**
     * 启用 SsoClient。仅 DISABLED 可启用，重复启用抛 409。
     */
    public void enable() {
        Assertions.require(status == SsoClientStatus.DISABLED, AppRegistryMessage.SSO_CLIENT_ALREADY_ENABLED);
        this.status = SsoClientStatus.ACTIVE;
    }
}
