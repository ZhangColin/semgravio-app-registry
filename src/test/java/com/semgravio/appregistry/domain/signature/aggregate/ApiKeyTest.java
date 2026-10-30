package com.semgravio.appregistry.domain.signature.aggregate;

import com.semgravio.appregistry.domain.error.AppRegistryMessage;
import com.semgravio.appregistry.domain.signature.enums.ApiKeyStatus;
import com.cartisan.core.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ApiKey} 聚合根领域逻辑单元测试。
 *
 * @since 0.1.0
 */
class ApiKeyTest {

    private static final long APP_ID = 1001L;

    @Test
    void givenValidInput_whenCreate_thenActiveWithIdAndFields() {
        ApiKey key = ApiKey.create(APP_ID, "ak_abc", "cipher_xyz");

        assertThat(key.getId()).isNotNull();
        assertThat(key.getAppId()).isEqualTo(APP_ID);
        assertThat(key.getApiKey()).isEqualTo("ak_abc");
        assertThat(key.getApiSecret()).isEqualTo("cipher_xyz");
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
    }

    @Test
    void givenExistingKey_whenRotate_thenSecretChangesAndStatusActive() {
        ApiKey key = ApiKey.create(APP_ID, "ak_old", "cipher_old");
        key.disable();

        key.rotate("cipher_new");

        assertThat(key.getApiKey()).isEqualTo("ak_old");
        assertThat(key.getApiSecret()).isEqualTo("cipher_new");
        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
    }

    @Test
    void givenActiveKey_whenDisable_thenDisabled() {
        ApiKey key = ApiKey.create(APP_ID, "ak", "cipher");

        key.disable();

        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.DISABLED);
    }

    @Test
    void givenDisabledKey_whenDisable_thenThrowsAlreadyDisabled() {
        ApiKey key = ApiKey.create(APP_ID, "ak", "cipher");
        key.disable();

        assertThatThrownBy(key::disable)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.API_KEY_ALREADY_DISABLED);
    }

    @Test
    void givenDisabledKey_whenEnable_thenActive() {
        ApiKey key = ApiKey.create(APP_ID, "ak", "cipher");
        key.disable();

        key.enable();

        assertThat(key.getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
    }

    @Test
    void givenActiveKey_whenEnable_thenThrowsAlreadyEnabled() {
        ApiKey key = ApiKey.create(APP_ID, "ak", "cipher");

        assertThatThrownBy(key::enable)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCodeMessage())
                .isEqualTo(AppRegistryMessage.API_KEY_ALREADY_ENABLED);
    }
}
