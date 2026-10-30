package com.semgravio.appregistry.domain.signature.aggregate;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * ApiKey 凭证生成——仅生成 {@code apiSecret} 明文。
 *
 * <p>{@code apiKey} 不再随机生成，直接使用 {@code RegisteredApp.appCode}。
 * 本工具只负责用 {@link SecureRandom} 生成 32 字节随机数 → Base64URL（无填充）作为
 * apiSecret。明文由应用层加密后入库、仅在创建/轮换响应里返一次。</p>
 *
 * <p>纯 JDK、无 Spring 依赖，可在领域层使用。</p>
 *
 * @since 0.1.0
 */
public final class ApiCredentials {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTE_LENGTH = 32;

    private ApiCredentials() {
    }

    /**
     * 生成一个新的明文 apiSecret（32-byte SecureRandom → Base64URL 无填充）。
     *
     * @return 明文 apiSecret
     */
    public static String generateSecret() {
        byte[] secretBytes = new byte[BYTE_LENGTH];
        RANDOM.nextBytes(secretBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    }
}
