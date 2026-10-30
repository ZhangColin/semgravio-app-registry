package com.semgravio.appregistry.domain.sso.aggregate;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * SsoClient 凭证生成（{@code client_id} + {@code client_secret} 明文）。
 *
 * <p>用 {@link SecureRandom} 生成 32 字节随机数 → Base64URL（无填充），与 ApiKey 的 {@code ApiCredentials}
 * 同款（ADR-0003 §6）。{@code client_id} 经撞名检查后入库；明文 {@code client_secret} 由应用层哈希后入库、
 * 仅在创建/轮换响应里返一次。</p>
 *
 * <p>纯 JDK、无 Spring 依赖，可在领域层使用。</p>
 *
 * @since 0.1.0
 */
public final class SsoCredentials {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTE_LENGTH = 32;

    private SsoCredentials() {
    }

    /**
     * 生成一对新凭证（client_id + 明文 client_secret）——创建 SsoClient 用：{@code client_id} 经撞名检查后入库，
     * 明文 {@code client_secret} 由应用层哈希入库、仅在响应里返一次。
     *
     * @return 生成的凭证对
     */
    public static Generated generate() {
        byte[] clientIdBytes = new byte[BYTE_LENGTH];
        RANDOM.nextBytes(clientIdBytes);
        String clientId = Base64.getUrlEncoder().withoutPadding().encodeToString(clientIdBytes);
        return new Generated(clientId, generateSecret());
    }

    /**
     * 仅生成明文 client_secret——重置凭证用：{@code client_id} 终身稳定、只换 secret（ADR-0005）。
     * secret 经 argon2 哈希、永不按值查表，故无需撞名检测。
     *
     * @return 生成的明文 client_secret（待哈希）
     */
    public static String generateSecret() {
        byte[] secretBytes = new byte[BYTE_LENGTH];
        RANDOM.nextBytes(secretBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    }

    /** 生成结果：client_id（OIDC 凭证标识）+ client_secret（明文，待哈希）。*/
    public record Generated(String clientId, String clientSecret) {
    }
}
