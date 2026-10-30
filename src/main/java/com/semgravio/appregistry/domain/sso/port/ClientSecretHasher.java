package com.semgravio.appregistry.domain.sso.port;

/**
 * SSO {@code client_secret} 单向哈希端口（南向）。
 *
 * <p>与 ApiKey 的可逆加密（{@link com.semgravio.appregistry.domain.signature.port.ApiSecretEncrypter}）
 * 相反：SSO {@code client_secret} 必须 <strong>hash-only</strong>（不可逆）——DB 只存 hash，比对由 identity
 * 本地完成（{@code client_secret_post}：应用发明文、identity 比对 hash）。</p>
 *
 * <p>本端口<strong>只暴露 {@link #hash(String)}</strong>，刻意不提供 verify/compare——比对是 SSO 流程职责、
 * 归 identity，app-registry 不做比对端点（ADR-0003 §4）。端口形态本身坐实边界。</p>
 *
 * @since 0.1.0
 */
public interface ClientSecretHasher {

    /** 把明文 client_secret 哈希为不可逆的 hash 字符串（含算法参数 + salt，供 identity 比对）。*/
    String hash(String plaintext);
}
