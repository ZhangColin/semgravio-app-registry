package com.semgravio.appregistry.application;

import com.semgravio.appregistry.domain.app.aggregate.RegisteredApp;
import com.semgravio.appregistry.domain.app.repository.RegisteredAppRepository;
import com.semgravio.appregistry.domain.signature.aggregate.ApiCredentials;
import com.semgravio.appregistry.domain.signature.aggregate.ApiKey;
import com.semgravio.appregistry.domain.signature.port.ApiSecretEncrypter;
import com.semgravio.appregistry.domain.signature.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台预置应用 seed——启动时确保 admin-console 应用及其 ApiKey 存在。
 *
 * <p>触发时机：{@link org.springframework.boot.ApplicationRunner} 在应用启动后调用。
 * seed 逻辑幂等：主密钥未变时重复执行不产生副作用；主密钥轮换后自动检测并轮换 secret。</p>
 *
 * @since 0.1.0
 */
@Service
public class PlatformSeedAppService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSeedAppService.class);

    static final String ADMIN_CONSOLE_APP_CODE = "admin-console";
    static final String ADMIN_CONSOLE_APP_NAME = "管理后台";

    /** admin-console 不可变 appCode（供其他服务引用，避免硬编码）。*/
    public static final String PLATFORM_APP_CODE = "admin-console";

    private final RegisteredAppRepository appRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiSecretEncrypter encrypter;

    public PlatformSeedAppService(RegisteredAppRepository appRepository, ApiKeyRepository apiKeyRepository,
                                  ApiSecretEncrypter encrypter) {
        this.appRepository = appRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.encrypter = encrypter;
    }

    /**
     * 执行 seed：确保 admin-console 应用 + ApiKey 存在且可用。
     */
    @Transactional
    public void seed() {
        RegisteredApp app = appRepository.findByAppCode(ADMIN_CONSOLE_APP_CODE)
                .orElseGet(this::createAdminConsole);
        ensureApiKey(app);
    }

    private RegisteredApp createAdminConsole() {
        RegisteredApp app = RegisteredApp.create(ADMIN_CONSOLE_APP_CODE, ADMIN_CONSOLE_APP_NAME, null);
        appRepository.saveAndFlush(app);
        return app;
    }

    private void ensureApiKey(RegisteredApp app) {
        apiKeyRepository.findByAppId(app.getId()).ifPresentOrElse(
                existing -> tryRotateIfNeeded(existing),
                () -> createApiKey(app));
    }

    private void tryRotateIfNeeded(ApiKey existing) {
        try {
            encrypter.decrypt(existing.getApiSecret());
            // 解密成功 → 主密钥未变，跳过
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 解密失败（AES-GCM 解密异常或 Base64 格式损坏）→ 主密钥已轮换，自动轮换 secret
            String plaintextSecret = ApiCredentials.generateSecret();
            existing.rotate(encrypter.encrypt(plaintextSecret));
            apiKeyRepository.saveAndFlush(existing);
            log.info("admin-console seed rotated, apiSecret: {}", plaintextSecret);
        }
    }

    private void createApiKey(RegisteredApp app) {
        String plaintextSecret = ApiCredentials.generateSecret();
        ApiKey key = ApiKey.create(app.getId(), app.getAppCode(), encrypter.encrypt(plaintextSecret));
        apiKeyRepository.saveAndFlush(key);
        log.info("admin-console seed created, apiSecret: {}", plaintextSecret);
    }
}
