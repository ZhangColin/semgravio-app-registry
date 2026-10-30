package com.semgravio.appregistry.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AesGcmApiSecretEncrypter} AES-GCM 加解密单元测试。
 *
 * @since 0.1.0
 */
class AesGcmApiSecretEncrypterTest {

    /** 32 字节 AES-256 密钥（Base64），与 application-test.yml 同款。*/
    private static final String KEY = "Hl7fZUXzcLKaPtHpni3ujMwaEMc6cX6PtDRfwaa9c3w=";

    @Test
    void givenPlaintext_whenEncryptThenDecrypt_thenRoundTrips() {
        AesGcmApiSecretEncrypter encrypter = new AesGcmApiSecretEncrypter(KEY);
        String plaintext = "super-secret-api-secret-12345";

        String ciphertext = encrypter.encrypt(plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(encrypter.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void givenSamePlaintext_whenEncryptTwice_thenCiphertextsDiffer() {
        AesGcmApiSecretEncrypter encrypter = new AesGcmApiSecretEncrypter(KEY);
        String plaintext = "same-secret";

        String first = encrypter.encrypt(plaintext);
        String second = encrypter.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(encrypter.decrypt(first)).isEqualTo(plaintext);
        assertThat(encrypter.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void givenTamperedCiphertext_whenDecrypt_thenThrows() {
        AesGcmApiSecretEncrypter encrypter = new AesGcmApiSecretEncrypter(KEY);
        String ciphertext = encrypter.encrypt("secret");
        String tampered = ciphertext.substring(0, ciphertext.length() - 2) + "AA";

        assertThatThrownBy(() -> encrypter.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void givenWrongKeyLength_whenConstruct_thenThrows() {
        // 3 字节，非法 AES-256 密钥
        assertThatThrownBy(() -> new AesGcmApiSecretEncrypter("AAAA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
