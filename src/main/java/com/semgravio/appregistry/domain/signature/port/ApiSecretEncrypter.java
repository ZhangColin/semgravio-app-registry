package com.semgravio.appregistry.domain.signature.port;

/**
 * 签名 {@code apiSecret} 可逆加密端口（南向）。
 *
 * <p>与 SsoClient 的 hash-only 相反：ApiKey 的 {@code apiSecret} 必须<strong>可取回</strong>
 * （验签要明文做 HMAC），故用对称加密（AES-GCM）而非单向 hash。DB 只存密文，
 * 内存中解密得明文组进 {@code ApiKeyInfo.apiSecret}。</p>
 *
 * @since 0.1.0
 */
public interface ApiSecretEncrypter {

    /** 加密明文 secret → 密文字符串（含 IV，base64）。*/
    String encrypt(String plaintext);

    /** 解密密文 → 明文 secret。*/
    String decrypt(String ciphertext);
}
