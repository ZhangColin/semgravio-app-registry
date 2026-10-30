package com.semgravio.appregistry.infrastructure;

import com.semgravio.appregistry.domain.sso.port.ClientSecretHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * argon2 实现 {@link ClientSecretHasher}（{@code spring-security-crypto} {@link Argon2PasswordEncoder}）。
 *
 * <p>用 Spring Security v5.8 默认参数（id 变体、salt 16B、hash 32B、memory 12MiB、iterations 3、parallelism 1）——
 * 抗 GPU/ASIC、适合低频的 client_secret 哈希。输出为自描述字符串（含算法参数 + salt），identity 可直接
 * {@code Argon2PasswordEncoder.matches} 比对。</p>
 *
 * <p>运行时依赖 BouncyCastle（pom 显式引入 {@code bcprov-jdk18on}，spring-security-crypto 声明为可选）。</p>
 *
 * <p>本类只 {@code encode}（哈希），不 {@code matches}——比对归 identity（见端口契约）。</p>
 *
 * @since 0.1.0
 */
@Component
public class Argon2ClientSecretHasher implements ClientSecretHasher {

    private final Argon2PasswordEncoder encoder;

    public Argon2ClientSecretHasher() {
        this.encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Override
    public String hash(String plaintext) {
        return encoder.encode(plaintext);
    }
}
