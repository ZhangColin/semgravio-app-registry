package com.semgravio.appregistry.application;

import com.semgravio.appregistry.application.dto.response.ApiKeyCreatedResponse;
import com.semgravio.appregistry.application.dto.response.ApiKeyResponse;
import com.semgravio.appregistry.application.mapper.ApiKeyMapper;
import com.semgravio.appregistry.domain.app.aggregate.RegisteredApp;
import com.semgravio.appregistry.domain.app.enums.RegisteredAppStatus;
import com.semgravio.appregistry.domain.app.repository.RegisteredAppRepository;
import com.semgravio.appregistry.domain.error.AppRegistryMessage;
import com.semgravio.appregistry.domain.signature.aggregate.ApiCredentials;
import com.semgravio.appregistry.domain.signature.aggregate.ApiKey;
import com.semgravio.appregistry.domain.signature.enums.ApiKeyStatus;
import com.semgravio.appregistry.domain.signature.port.ApiSecretEncrypter;
import com.semgravio.appregistry.domain.signature.repository.ApiKeyRepository;
import com.cartisan.core.exception.BaseCodeMessage;
import com.cartisan.core.exception.DomainException;
import com.cartisan.openapi.provider.ApiKeyInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ApiKey 应用服务——创建/轮换、查询、禁用/启用，以及解析为框架 {@link ApiKeyInfo}。
 *
 * <p>明文 {@code apiSecret} 仅在创建/轮换响应里返一次：生成（SecureRandom）→ 加密（AES-GCM）→
 * 入库密文 → 明文进响应。之后任何接口都不再返回明文。</p>
 *
 * <p>{@link #resolveApiKeyInfo(String)} 同时服务 bootstrap 端点 {@code GET /api-keys/{apiKey}}
 * 与 {@link LocalApiKeyProvider}：按 {@code api_key} 查、join app 校验状态
 * ({@code key.status && app.status})、内存解密组 {@code ApiKeyInfo(apiKey, appName, apiSecret=明文)}。
 * key 或 app 任一禁用/不存在 → 返回 {@link Optional#empty()}（框架视为不可用）。</p>
 *
 * @since 0.1.0
 */
@Service
public class ApiKeyAppService {

    private final ApiKeyRepository apiKeyRepository;
    private final RegisteredAppRepository appRepository;
    private final ApiSecretEncrypter encrypter;
    private final ApiKeyMapper mapper;

    public ApiKeyAppService(ApiKeyRepository apiKeyRepository, RegisteredAppRepository appRepository,
                            ApiSecretEncrypter encrypter, ApiKeyMapper mapper) {
        this.apiKeyRepository = apiKeyRepository;
        this.appRepository = appRepository;
        this.encrypter = encrypter;
        this.mapper = mapper;
    }

    /**
     * 创建或轮换应用的 ApiKey（1:1，同一端点两用）。
     *
     * <p>apiKey = app.getAppCode()（创建后不可变）。已有活跃 ApiKey → 原地轮换（只换 secret，重置 ACTIVE）；
     * 否则新建。响应一次性返回明文 {@code apiSecret}。</p>
     */
    @Transactional
    public ApiKeyCreatedResponse createOrRotate(Long appId) {
        RegisteredApp app = loadApp(appId);
        String apiKey = app.getAppCode();
        String plaintextSecret = ApiCredentials.generateSecret();

        Optional<ApiKey> existing = apiKeyRepository.findByAppId(appId);
        ApiKey key;
        if (existing.isPresent()) {
            key = existing.get();
            key.rotate(encrypter.encrypt(plaintextSecret));
        } else {
            key = ApiKey.create(appId, apiKey, encrypter.encrypt(plaintextSecret));
        }
        apiKeyRepository.saveAndFlush(key);
        return mapper.toCreated(key, plaintextSecret);
    }

    /**
     * 查询应用的 ApiKey（不含明文 secret）。应用或 ApiKey 不存在返 404。
     */
    @Transactional(readOnly = true)
    public ApiKeyResponse getByAppId(Long appId) {
        loadApp(appId);
        return mapper.convert(loadKeyByApp(appId));
    }

    /**
     * 禁用 ApiKey。重复禁用返 409。admin-console 不可禁用。
     */
    @Transactional
    public ApiKeyResponse disable(Long appId) {
        RegisteredApp app = loadApp(appId);
        guardPlatformApp(app);
        ApiKey key = loadKeyByApp(appId);
        key.disable();
        apiKeyRepository.saveAndFlush(key);
        return mapper.convert(key);
    }

    /**
     * 启用 ApiKey。重复启用返 409。
     */
    @Transactional
    public ApiKeyResponse enable(Long appId) {
        loadApp(appId);
        ApiKey key = loadKeyByApp(appId);
        key.enable();
        apiKeyRepository.saveAndFlush(key);
        return mapper.convert(key);
    }

    /**
     * 解析 {@code apiKey} → 框架 {@link ApiKeyInfo}（校验 key.status && app.status 均 ACTIVE）。
     *
     * <p>供 bootstrap 端点 {@code GET /api-keys/{apiKey}} 与 {@link LocalApiKeyProvider} 共用。
     * key 或 app 任一禁用/不存在 → 返回 {@link Optional#empty()}（框架拒签）。明文 secret 内存解密。</p>
     *
     * @param apiKey 凭证标识（= 框架 X-App-Id）
     * @return ApiKeyInfo；key/app 任一不可用返 empty
     */
    @Transactional(readOnly = true)
    public Optional<ApiKeyInfo> resolveApiKeyInfo(String apiKey) {
        return apiKeyRepository.findByApiKey(apiKey)
                .filter(key -> key.getStatus() == ApiKeyStatus.ACTIVE)
                .flatMap(key -> {
                    Optional<RegisteredApp> app = appRepository.findById(key.getAppId());
                    return app.filter(a -> a.getStatus() == RegisteredAppStatus.ACTIVE)
                            .map(a -> new ApiKeyInfo(key.getApiKey(), a.getName(), encrypter.decrypt(key.getApiSecret())));
                });
    }

    private RegisteredApp loadApp(Long appId) {
        return appRepository.findById(appId)
                .orElseThrow(() -> new DomainException(BaseCodeMessage.RESOURCE_NOT_FOUND, appId));
    }

    private void guardPlatformApp(RegisteredApp app) {
        if (PlatformSeedAppService.PLATFORM_APP_CODE.equals(app.getAppCode())) {
            throw new DomainException(AppRegistryMessage.ADMIN_CONSOLE_CANNOT_DISABLE, app.getAppCode());
        }
    }

    private ApiKey loadKeyByApp(Long appId) {
        return apiKeyRepository.findByAppId(appId)
                .orElseThrow(() -> new DomainException(BaseCodeMessage.RESOURCE_NOT_FOUND, appId));
    }
}
