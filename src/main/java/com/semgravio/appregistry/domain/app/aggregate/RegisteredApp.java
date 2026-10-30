package com.semgravio.appregistry.domain.app.aggregate;

import com.semgravio.appregistry.domain.app.enums.RegisteredAppStatus;
import com.semgravio.appregistry.domain.error.AppRegistryMessage;
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
 * 已登记应用（聚合根）——ApiKey 与 SsoClient 的共同宿主与治理锚点。
 *
 * <p>一个应用一行：持 {@code app_code}（稳定公开 slug，创建时填写、不可修改）、
 * name、description、应用级 status。两类凭证（{@code ApiKey}/{@code SsoClient}）独立挂在 app 上、可空，
 * 以 app 内部 {@code id} 关联。</p>
 *
 * <h3>状态机</h3>
 * <ul>
 *   <li>create → {@link RegisteredAppStatus#ACTIVE}</li>
 *   <li>{@link #disable()} ACTIVE → DISABLED；{@link #enable()} DISABLED → ACTIVE。</li>
 *   <li>重复禁用/启用抛 {@link com.cartisan.core.exception.DomainException}（409），状态机显式不幂等。</li>
 * </ul>
 *
 * <h3>不变量</h3>
 * <ul>
 *   <li>{@code app_code} 创建时校验、之后不可变（无 setter；列 {@code updatable=false}）。</li>
 *   <li>{@code id} 由 {@link TsidGenerator} 在工厂内分配（出生即有，不依赖 flush / DB 自增）。</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Entity
@Table(name = "ar_registered_apps")
@Aggregate
// DATA-004：@SQLRestriction 在 @MappedSuperclass 上继承不可靠，实体类重复声明确保软删过滤生效。
@SQLRestriction("deleted = false")
@Getter
public class RegisteredApp extends AuditableSoftDeletable implements AggregateRoot<RegisteredApp, Long> {

    /** app_code slug 规则：4-64 位小写字母/数字/连字符，不以连字符开头或结尾。 */
    static final String APP_CODE_PATTERN = "^[a-z0-9][a-z0-9-]{2,62}[a-z0-9]$";

    /** 应用名称最大长度（与 DB {@code name} 列 / DTO @Size 保持一致）。 */
    public static final int NAME_MAX_LENGTH = 128;

    /** 应用描述最大长度（与 DB {@code description} 列 / DTO @Size 保持一致）。 */
    public static final int DESCRIPTION_MAX_LENGTH = 512;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_code", nullable = false, updatable = false, length = 64)
    private String appCode;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(name = "status", nullable = false)
    private RegisteredAppStatus status;

    protected RegisteredApp() {
    }

    private RegisteredApp(String appCode, String name, String description) {
        Assertions.require(
                appCode != null && appCode.matches(APP_CODE_PATTERN),
                AppRegistryMessage.APP_CODE_INVALID, appCode);
        Assertions.require(
                name != null && !name.isBlank(),
                AppRegistryMessage.APP_NAME_REQUIRED);
        this.id = TsidGenerator.newInstance().generate();
        this.appCode = appCode;
        this.name = name;
        this.description = description;
        this.status = RegisteredAppStatus.ACTIVE;
    }

    /**
     * 创建应用（工厂）。app_code 校验通过后分配 id、置 ACTIVE。
     *
     * @param appCode     稳定公开 slug（不可变）
     * @param name        应用名
     * @param description 描述（可空）
     * @return 新建的、尚未持久化的应用
     */
    public static RegisteredApp create(String appCode, String name, String description) {
        return new RegisteredApp(appCode, name, description);
    }

    /**
     * 禁用应用。仅 ACTIVE 可禁用，重复禁用抛 409。
     */
    public void disable() {
        Assertions.require(status == RegisteredAppStatus.ACTIVE,
                AppRegistryMessage.APP_ALREADY_DISABLED, appCode);
        this.status = RegisteredAppStatus.DISABLED;
    }

    /**
     * 启用应用。仅 DISABLED 可启用，重复启用抛 409。
     */
    public void enable() {
        Assertions.require(status == RegisteredAppStatus.DISABLED,
                AppRegistryMessage.APP_ALREADY_ENABLED, appCode);
        this.status = RegisteredAppStatus.ACTIVE;
    }

    /**
     * 更新应用基本信息（name、description）。已禁用应用仍可修改。
     *
     * @param name        新应用名（不可为空）
     * @param description 新描述（可空）
     */
    public void update(String name, String description) {
        Assertions.require(
                name != null && !name.isBlank(),
                AppRegistryMessage.APP_NAME_REQUIRED);
        this.name = name;
        this.description = description;
    }
}
