package com.semgravio.appregistry.application;

import com.cartisan.openapi.provider.ApiKeyInfo;
import com.cartisan.openapi.provider.ApiKeyProvider;
import org.springframework.stereotype.Component;

/**
 * 本服务的 {@link ApiKeyProvider} 实现（覆盖框架默认的 {@code RemoteApiKeyProvider}）。
 *
 * <p>直读自己表、内存解密，组 {@link ApiKeyInfo}——<strong>不自调 HTTP</strong>
 * （本服务即登记处，无需绕一圈 HTTP 调自己的 bootstrap 端点）。</p>
 *
 * <p>框架契约 {@code getByApiKey(String apiKey)} 的入参 {@code apiKey} 即 {@code api_key}
 * （{@code callerAppId = X-App-Id = api_key}，框架禁改）——此处按 {@code api_key} 查。</p>
 *
 * @since 0.1.0
 */
@Component
public class LocalApiKeyProvider implements ApiKeyProvider {

    private final ApiKeyAppService apiKeyAppService;

    public LocalApiKeyProvider(ApiKeyAppService apiKeyAppService) {
        this.apiKeyAppService = apiKeyAppService;
    }

    @Override
    public ApiKeyInfo getByApiKey(String apiKey) {
        return apiKeyAppService.resolveApiKeyInfo(apiKey).orElse(null);
    }
}
