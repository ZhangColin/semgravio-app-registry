package com.semgravio.appregistry.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Argon2ClientSecretHasher} argon2 哈希单元测试。
 *
 * <p>注意：hash-only 不可逆——无「解密回明文」测试；正确性用 {@code Argon2PasswordEncoder.matches}
 * （identity 侧同款）验证「明文 ↔ hash」可比对。</p>
 *
 * @since 0.1.0
 */
class Argon2ClientSecretHasherTest {

    @Test
    void givenPlaintext_whenHash_thenArgon2FormatAndNotPlaintext() {
        Argon2ClientSecretHasher hasher = new Argon2ClientSecretHasher();
        String plaintext = "super-secret-client-secret-12345";

        String hash = hasher.hash(plaintext);

        assertThat(hash).isNotEqualTo(plaintext);
        assertThat(hash).startsWith("$argon2");
    }

    @Test
    void givenSamePlaintext_whenHashTwice_thenHashesDifferButBothMatch() {
        Argon2ClientSecretHasher hasher = new Argon2ClientSecretHasher();
        String plaintext = "same-secret";

        String first = hasher.hash(plaintext);
        String second = hasher.hash(plaintext);

        // 随机 salt → 同明文不同 hash
        assertThat(first).isNotEqualTo(second);
        // 但都能被 identity 侧同款 encoder 比对为真（hash-only 的「可验证」）
        Argon2PasswordEncoder verifier = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        assertThat(verifier.matches(plaintext, first)).isTrue();
        assertThat(verifier.matches(plaintext, second)).isTrue();
    }

    @Test
    void givenWrongPlaintext_whenMatches_thenFalse() {
        Argon2ClientSecretHasher hasher = new Argon2ClientSecretHasher();
        String hash = hasher.hash("correct-secret");

        Argon2PasswordEncoder verifier = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        assertThat(verifier.matches("wrong-secret", hash)).isFalse();
    }
}
