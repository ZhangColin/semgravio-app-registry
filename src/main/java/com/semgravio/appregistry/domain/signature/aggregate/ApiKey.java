package com.semgravio.appregistry.domain.signature.aggregate;

import com.semgravio.appregistry.domain.error.AppRegistryMessage;
import com.semgravio.appregistry.domain.signature.enums.ApiKeyStatus;
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
import org.hibernate.annotations.SQLRestriction;

/**
 * ApiKey 聚合根——机机验签凭证（{@code apiKey}/{@code apiSecret}）。
 *
 * <p>1:1 挂在 {@code RegisteredApp} 上、可空。{@code apiKey} = {@code RegisteredApp.appCode}
 * （= 框架 {@code X-App-Id}/{@code callerAppId}），创建时设定不可变；{@code apiSecret} 以
 * <strong>AES-GCM 密文</strong>入库（明文不入库、不入日志），内存解密供 HMAC 验签。
 * 明文仅由应用层在创建/轮换时生成、加密、返响应一次。</p>
 *
 * <h3>状态机</h3>
 * <ul>
 *   <li>create → {@link ApiKeyStatus#ACTIVE}；rotate → 重置 ACTIVE 并换新凭证。</li>
 *   <li>{@link #disable()} ACTIVE → DISABLED；{@link #enable()} DISABLED → ACTIVE。重复转换抛 409。</li>
 * </ul>
 *
 * <h3>组合生效</h3>
 * <p>本聚合 status 仅是自身状态；验签是否放行由
 * {@code ApiKey.status && RegisteredApp.status} 联合决定（app 禁用则凭证失效）——
 * 该级联在 {@code LocalApiKeyProvider}/bootstrap 端点查询时 join app 计算，不在本聚合。</p>
 *
 * @since 0.1.0
 */
@Entity
@Table(name = "ar_api_keys")
@Aggregate
// DATA-004：@SQLRestriction 在 @MappedSuperclass 上继承不可靠，实体类重复声明确保软删过滤生效。
@SQLRestriction("deleted = false")
@Getter
public class ApiKey extends AuditableSoftDeletable implements AggregateRoot<ApiKey, Long> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_id", nullable = false, updatable = false)
    private Long appId;

    @Column(name = "api_key", nullable = false, length = 128)
    private String apiKey;

    /** AES-GCM 密文（含 IV，base64）。明文绝不落库。*/
    @Column(name = "api_secret", nullable = false, length = 512)
    private String apiSecret;

    @Column(name = "status", nullable = false)
    private ApiKeyStatus status;

    protected ApiKey() {
    }

    private ApiKey(Long appId, String apiKey, String encryptedSecret) {
        this.id = TsidGenerator.newInstance().generate();
        this.appId = appId;
        this.apiKey = apiKey;
        this.apiSecret = encryptedSecret;
        this.status = ApiKeyStatus.ACTIVE;
    }

    /**
     * 创建 ApiKey（工厂）。{@code apiKey} = {@code appCode}（= 框架 X-App-Id），
     * 密文由应用层生成（AES-GCM）后传入。
     *
     * @param appId           所属应用 id
     * @param apiKey          凭证标识（= appCode，创建后不可变）
     * @param encryptedSecret AES-GCM 密文
     * @return 新建的、尚未持久化的 ApiKey
     */
    public static ApiKey create(Long appId, String apiKey, String encryptedSecret) {
        return new ApiKey(appId, apiKey, encryptedSecret);
    }

    /**
     * 轮换凭证：换新密文，重置 ACTIVE（应用层先生成+加密再传入）。
     * apiKey 创建时设定（= appCode），不可变。
     *
     * @param newEncryptedSecret 新 apiSecret 密文
     */
    public void rotate(String newEncryptedSecret) {
        // 入参由应用层生成（SecureRandom + AES），由调用方保证非空，无需领域断言。
        this.apiSecret = newEncryptedSecret;
        this.status = ApiKeyStatus.ACTIVE;
    }

    /**
     * 禁用 ApiKey。仅 ACTIVE 可禁用，重复禁用抛 409。
     */
    public void disable() {
        Assertions.require(status == ApiKeyStatus.ACTIVE, AppRegistryMessage.API_KEY_ALREADY_DISABLED);
        this.status = ApiKeyStatus.DISABLED;
    }

    /**
     * 启用 ApiKey。仅 DISABLED 可启用，重复启用抛 409。
     */
    public void enable() {
        Assertions.require(status == ApiKeyStatus.DISABLED, AppRegistryMessage.API_KEY_ALREADY_ENABLED);
        this.status = ApiKeyStatus.ACTIVE;
    }
}
